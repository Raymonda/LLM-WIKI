#!/usr/bin/env bash
# ============================================================
#  LLM Wiki - Python Environment Setup (CentOS 8 / CentOS Stream 8)
#  Installs Python 3.11+ via dnf, creates venv, installs deps
#
#  踩坑经验（2026-05 实战总结）：
#  1. CentOS 8 已 EOL，默认 mirrorlist 不可用，必须切换 vault 仓库
#  2. python3.12 可能不在默认仓库，需要逐级降级尝试（3.12→3.11→3.9）
#  3. 国内网络访问 PyPI 经常超时，必须使用阿里云镜像 + --timeout
#  4. pip 批量安装某个包失败会中止整个流程，需要逐个回退安装
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv-python"
REQUIREMENTS="$SCRIPT_DIR/tools/requirements.txt"
ALIYUN_MIRROR="https://mirrors.aliyun.com/pypi/simple/"
PIP_TIMEOUT=120

echo ""
echo "============ LLM Wiki Python Environment Setup (CentOS 8) ============"
echo ""

# ================================================================
# STEP 1: 权限检测
# ================================================================
if [ "$(id -u)" -eq 0 ]; then
    IS_ROOT=true
    echo "Running as root."
else
    IS_ROOT=false
    echo "Running as non-root user. Will use sudo for system package installation."
fi

run_as_root() {
    if [ "$IS_ROOT" = true ]; then
        "$@"
    else
        sudo "$@"
    fi
}

# ================================================================
# STEP 2: 修复 CentOS 8 EOL 仓库（vault 切换）
# ================================================================
if [ -f /etc/centos-release ] && grep -qi "CentOS Linux release 8" /etc/centos-release 2>/dev/null; then
    echo "Detected CentOS 8 (EOL). Switching to vault repos..."
    run_as_root sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
    run_as_root sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    echo "Repos updated."
    echo ""
fi

# ================================================================
# STEP 3: 探测 Python（优先已有 venv → 系统命令 → 自动安装）
# ================================================================
PYTHON_CMD=""
echo "Detecting Python..."

# 3a. 检查项目内已有 venv
if [ -x "$VENV_DIR/bin/python" ]; then
    version_output=$("$VENV_DIR/bin/python" --version 2>&1 || true)
    if echo "$version_output" | grep -qi "python"; then
        PYTHON_CMD="$VENV_DIR/bin/python"
        echo "Found existing venv: $PYTHON_CMD"
    fi
fi

# 3b. 遍历系统命令（优先高版本）
if [ -z "$PYTHON_CMD" ]; then
    for cmd in python3.12 python3.11 python3.9 python3; do
        if command -v "$cmd" &>/dev/null; then
            version_output=$("$cmd" --version 2>&1 || true)
            if echo "$version_output" | grep -qi "python"; then
                PYTHON_CMD="$cmd"
                break
            fi
        fi
    done
fi

# 3c. 自动安装（逐级降级）
if [ -z "$PYTHON_CMD" ]; then
    echo "Python not found. Installing via dnf..."
    run_as_root dnf install -y python3.12 python3.12-devel python3.12-pip 2>/dev/null || \
    run_as_root dnf install -y python3.11 python3.11-devel python3.11-pip 2>/dev/null || \
    run_as_root dnf install -y python39 python39-devel python39-pip 2>/dev/null || \
    run_as_root dnf module install -y python39 2>/dev/null || {
        echo "[ERROR] Failed to install Python via dnf."
        echo "        Try: sudo dnf install python3 python3-devel python3-pip"
        exit 1
    }
    # 重新探测
    for cmd in python3.12 python3.11 python3.9 python3; do
        if command -v "$cmd" &>/dev/null; then
            version_output=$("$cmd" --version 2>&1 || true)
            if echo "$version_output" | grep -qi "python"; then
                PYTHON_CMD="$cmd"
                break
            fi
        fi
    done
fi

if [ -z "$PYTHON_CMD" ]; then
    echo "[ERROR] Python installation failed."
    exit 1
fi

echo "Found: $($PYTHON_CMD --version 2>&1) ($PYTHON_CMD)"
echo ""

# ================================================================
# STEP 4: 确保 venv 模块可用
# ================================================================
if ! $PYTHON_CMD -m venv --help &>/dev/null; then
    echo "[WARN] venv module not available. Installing..."
    PY_VER=$($PYTHON_CMD -c "import sys; print(f'{sys.version_info.major}{sys.version_info.minor}')")
    run_as_root dnf install -y "python${PY_VER}-venv" 2>/dev/null || \
    run_as_root dnf install -y python3-libs 2>/dev/null || {
        echo "[WARN] Could not install venv package. Trying alternative..."
        run_as_root dnf install -y python3 2>/dev/null || true
    }
fi
echo ""

# ================================================================
# STEP 5: 创建虚拟环境
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
# STEP 6: 激活 venv 并安装依赖（阿里云镜像 + 超时保护）
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
# STEP 7: 验证安装结果
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
echo "======================================================================="
