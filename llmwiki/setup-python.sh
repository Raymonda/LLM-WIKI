#!/usr/bin/env bash
# ============================================================
#  LLM Wiki - Python Environment Setup (Linux/Mac)
#  Creates a virtual environment and installs doc_parser deps
#
#  踩坑经验（2026-05 实战总结）：
#  1. 国内网络访问 PyPI 经常超时，必须使用阿里云镜像 + --timeout
#  2. Ubuntu 最小化安装缺少 python3-venv 模块，需提前检测并安装
#  3. pip 批量安装某个包失败会中止整个流程，需要逐个回退安装
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv-python"
REQUIREMENTS="$SCRIPT_DIR/tools/requirements.txt"
ALIYUN_MIRROR="https://mirrors.aliyun.com/pypi/simple/"
PIP_TIMEOUT=120

echo ""
echo "============ LLM Wiki Python Environment Setup ============"
echo ""

# ================================================================
# STEP 1: 探测 Python（优先使用已有 venv）
# ================================================================
PYTHON_CMD=""
echo "Detecting Python..."

# 1a. 检查项目内已有 venv
if [ -x "$VENV_DIR/bin/python" ]; then
    version_output=$("$VENV_DIR/bin/python" --version 2>&1 || true)
    if echo "$version_output" | grep -qi "python"; then
        PYTHON_CMD="$VENV_DIR/bin/python"
        echo "Found existing venv: $PYTHON_CMD"
    fi
fi

# 1b. 遍历系统命令
if [ -z "$PYTHON_CMD" ]; then
    for cmd in python3 python py; do
        if command -v "$cmd" &>/dev/null; then
            version_output=$("$cmd" --version 2>&1 || true)
            if echo "$version_output" | grep -qi "python"; then
                PYTHON_CMD="$cmd"
                break
            fi
        fi
    done
fi

# 1c. 自动安装（apt / brew）
if [ -z "$PYTHON_CMD" ]; then
    echo "Python not found."
    if command -v apt-get &>/dev/null; then
        echo "[AUTO] Installing Python 3 via apt..."
        sudo apt-get update -qq
        sudo apt-get install -y python3 python3-venv python3-pip
        PYTHON_CMD="python3"
    elif command -v brew &>/dev/null; then
        echo "[AUTO] Installing Python 3 via Homebrew..."
        brew install python3
        PYTHON_CMD="python3"
    else
        echo "[ERROR] Python not found. Please install Python 3.8+ first."
        echo "        Ubuntu/Debian: sudo apt install python3 python3-venv python3-pip"
        echo "        macOS:         brew install python3"
        exit 1
    fi
fi

echo "Found: $($PYTHON_CMD --version 2>&1) ($PYTHON_CMD)"
echo ""

# ================================================================
# STEP 2: 确保 venv 模块可用
# ================================================================
if ! $PYTHON_CMD -m venv --help &>/dev/null; then
    echo "[WARN] venv module not available. Installing..."
    if command -v apt-get &>/dev/null; then
        sudo apt-get install -y python3-venv || {
            PY_VER=$($PYTHON_CMD -c "import sys; print(f'{sys.version_info.major}{sys.version_info.minor}')")
            sudo apt-get install -y "python${PY_VER}-venv" 2>/dev/null || {
                echo "[ERROR] Cannot install python3-venv. Please install manually."
                exit 1
            }
        }
    elif command -v dnf &>/dev/null; then
        sudo dnf install -y python3-libs || sudo dnf install -y python3
    elif command -v yum &>/dev/null; then
        sudo yum install -y python3-libs || sudo yum install -y python3
    fi
fi

# ================================================================
# STEP 3: 创建虚拟环境
# ================================================================
if [ -d "$VENV_DIR" ]; then
    echo "Virtual environment already exists: $VENV_DIR"
else
    echo "Creating virtual environment: $VENV_DIR"
    $PYTHON_CMD -m venv "$VENV_DIR" || {
        echo "[ERROR] Failed to create venv."
        echo "        Try: $PYTHON_CMD -m ensurepip --default-pip"
        exit 1
    }
fi
echo ""

# ================================================================
# STEP 4: 激活 venv 并安装依赖（阿里云镜像 + 超时保护）
# ================================================================
echo "Activating virtual environment..."
source "$VENV_DIR/bin/activate"

echo "Upgrading pip..."
python -m pip install --upgrade pip -q -i "$ALIYUN_MIRROR" --trusted-host mirrors.aliyun.com --timeout "$PIP_TIMEOUT" 2>&1 || {
    echo "[WARN] pip upgrade failed, continuing with existing pip..."
}
echo ""

echo "Installing dependencies from $REQUIREMENTS..."
echo "(Using Aliyun mirror: $ALIYUN_MIRROR)"
echo ""
python -m pip install -r "$REQUIREMENTS" -i "$ALIYUN_MIRROR" --trusted-host mirrors.aliyun.com --timeout "$PIP_TIMEOUT" 2>&1 || {
    echo ""
    echo "[WARN] Batch install had failures. Trying required packages one by one..."
    echo ""
    INSTALL_FAILED=0
    for pkg in PyMuPDF python-docx openpyxl python-pptx pypdf; do
        echo -n "Installing $pkg... "
        if python -m pip install "$pkg" -i "$ALIYUN_MIRROR" --trusted-host mirrors.aliyun.com --timeout "$PIP_TIMEOUT" -q 2>&1; then
            echo "[OK]"
        else
            echo "[FAILED] (REQUIRED)"
            INSTALL_FAILED=1
        fi
    done
    echo ""
    echo "Installing optional packages..."
    for pkg in pdfplumber pandas; do
        echo -n "Installing $pkg... "
        if python -m pip install "$pkg" -i "$ALIYUN_MIRROR" --trusted-host mirrors.aliyun.com --timeout "$PIP_TIMEOUT" -q 2>&1; then
            echo "[OK]"
        else
            echo "[SKIPPED] (optional)"
        fi
    done
    if [ "$INSTALL_FAILED" -eq 1 ]; then
        echo ""
        echo "[ERROR] Some required packages failed to install."
        exit 1
    fi
}
echo ""

# ================================================================
# STEP 5: 验证安装结果
# ================================================================
echo "Verifying installation..."
python -c "import fitz; import docx; import openpyxl; import pptx; import pypdf; print('[OK] All required packages verified')" || {
    echo ""
    echo "[ERROR] Package verification failed."
    echo "        Try: $VENV_DIR/bin/python -m pip install PyMuPDF python-docx openpyxl python-pptx pypdf"
    exit 1
}

python -c "import pdfplumber; import pandas; print('[OK] Optional packages verified')" 2>/dev/null || {
    echo "[WARN] Some optional packages missing (pdfplumber/pandas). These features will be skipped."
}

echo ""
echo "============ Setup Complete ============"
echo ""
echo "Virtual environment: $VENV_DIR"
echo "Activate manually:   source $VENV_DIR/bin/activate"
echo ""
echo "The Java app will auto-detect the venv Python at:"
echo "  $VENV_DIR/bin/python"
echo "============================================================"
