@echo off
REM ============================================================
REM  LLM Wiki - Python Environment Setup (Windows)
REM  Creates a virtual environment and installs doc_parser deps
REM
REM  踩坑经验（2026-05 实战总结）：
REM  1. winget 安装后当前 shell 的 PATH 不会自动刷新，必须从注册表重新读取
REM  2. Windows Store 的 python.exe 是 stub（退出码 0 但无输出），必须过滤
REM  3. PowerShell 执行策略可能阻止 activate.ps1，统一使用 activate.bat
REM  4. 国内网络访问 PyPI 经常超时，必须使用阿里云镜像 + --timeout
REM ============================================================
setlocal enabledelayedexpansion

set VENV_DIR=.venv-python
set REQUIREMENTS=tools\requirements.txt
set ALIYUN_MIRROR=https://mirrors.aliyun.com/pypi/simple/
set PIP_TIMEOUT=120

echo.
echo ============ LLM Wiki Python Environment Setup ============
echo.

REM ================================================================
REM STEP 0: 补充常见 Python 安装路径到 PATH
REM （解决 winget 安装后当前 shell 的 PATH 不自动刷新的问题）
REM 注意：不能从注册表直接替换 PATH，因为注册表值含 %%SystemRoot%% 等未展开变量
REM ================================================================
echo Refreshing PATH...
REM 用户级 AppData 路径（winget 安装 Python 默认位置）
set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python312;%LOCALAPPDATA%\Programs\Python\Python312\Scripts"
set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python311;%LOCALAPPDATA%\Programs\Python\Python311\Scripts"
set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python310;%LOCALAPPDATA%\Programs\Python\Python310\Scripts"
set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python39;%LOCALAPPDATA%\Programs\Python\Python39\Scripts"
REM 系统级安装路径
set "PATH=%PATH%;C:\Python312;C:\Python311;C:\Python310;C:\Python39"
REM winget 默认安装路径（64 位 + 32 位）
set "PATH=%PATH%;%ProgramFiles%\Python312;%ProgramFiles%\Python312\Scripts"
set "PATH=%PATH%;%ProgramFiles%\Python311;%ProgramFiles%\Python311\Scripts"

REM ================================================================
REM STEP 1: 探测 Python（过滤 Windows Store stub）
REM ================================================================
set PYTHON_CMD=
echo Detecting Python...

REM 1a. 先检查项目内 venv（已存在则直接使用）
if exist "%VENV_DIR%\Scripts\python.exe" (
    for /f "tokens=*" %%v in ('"%VENV_DIR%\Scripts\python.exe" --version 2^>^&1') do (
        echo %%v | findstr /i "Python" >nul
        if not errorlevel 1 (
            set "PYTHON_CMD=%VENV_DIR%\Scripts\python.exe"
            echo Found existing venv: !PYTHON_CMD!
        )
    )
)

REM 1b. 遍历系统命令
if not defined PYTHON_CMD (
    for %%c in (python python3 py) do (
        if not defined PYTHON_CMD (
            where %%c >nul 2>&1
            if not errorlevel 1 (
                %%c --version >nul 2>&1
                if not errorlevel 1 (
                    for /f "tokens=*" %%v in ('%%c --version 2^>^&1') do (
                        REM 关键：过滤 Windows Store stub（退出码 0 但输出不含 "Python"）
                        echo %%v | findstr /i "Python" >nul
                        if not errorlevel 1 set "PYTHON_CMD=%%c"
                    )
                )
            )
        )
    )
)

REM 1c. 如果还没找到，尝试 winget 自动安装
if not defined PYTHON_CMD (
    echo.
    echo Python not found in PATH.
    echo.
    where winget >nul 2>&1
    if not errorlevel 1 (
        echo [AUTO] Attempting to install Python 3.12 via winget...
        winget install Python.Python.3.12 --silent --accept-package-agreements --accept-source-agreements
        if not errorlevel 1 (
            echo.
            echo Re-scanning PATH after winget install...
            set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python312;%LOCALAPPDATA%\Programs\Python\Python312\Scripts"
            set "PATH=%PATH%;%LOCALAPPDATA%\Programs\Python\Python311;%LOCALAPPDATA%\Programs\Python\Python311\Scripts"
            REM 重新探测
            for %%c in (python python3 py) do (
                if not defined PYTHON_CMD (
                    where %%c >nul 2>&1
                    if not errorlevel 1 (
                        %%c --version >nul 2>&1
                        if not errorlevel 1 (
                            for /f "tokens=*" %%v in ('%%c --version 2^>^&1') do (
                                echo %%v | findstr /i "Python" >nul
                                if not errorlevel 1 set "PYTHON_CMD=%%c"
                            )
                        )
                    )
                )
            )
        ) else (
            echo [WARN] winget install failed. Please install Python manually.
        )
    ) else (
        echo [INFO] winget not available. Please install Python manually.
    )
)

if not defined PYTHON_CMD (
    echo.
    echo [ERROR] Python not found. Please install Python 3.8+ first.
    echo         Download: https://www.python.org/downloads/
    echo         Or run:   winget install Python.Python.3.12
    exit /b 1
)

for /f "tokens=*" %%v in ('%PYTHON_CMD% --version 2^>^&1') do echo Found: %%v (%PYTHON_CMD%)
echo.

REM ================================================================
REM STEP 2: 创建虚拟环境
REM ================================================================
if exist "%VENV_DIR%" (
    echo Virtual environment already exists: %VENV_DIR%
) else (
    echo Creating virtual environment: %VENV_DIR%
    %PYTHON_CMD% -m venv %VENV_DIR%
    if errorlevel 1 (
        echo.
        echo [ERROR] Failed to create virtual environment.
        echo         Try: %PYTHON_CMD% -m ensurepip --default-pip
        exit /b 1
    )
)
echo.

REM ================================================================
REM STEP 3: 激活 venv 并安装依赖（阿里云镜像 + 超时重试）
REM ================================================================
echo Activating virtual environment...
call %VENV_DIR%\Scripts\activate.bat
if errorlevel 1 (
    echo [ERROR] Failed to activate venv. Try running as Administrator.
    exit /b 1
)

echo Upgrading pip...
python -m pip install --upgrade pip -q -i %ALIYUN_MIRROR% --trusted-host mirrors.aliyun.com --timeout %PIP_TIMEOUT%
if errorlevel 1 (
    echo [WARN] pip upgrade failed, continuing with existing pip...
)
echo.

echo Installing dependencies from %REQUIREMENTS%...
echo (Using Aliyun mirror: %ALIYUN_MIRROR%)
echo.
python -m pip install -r %REQUIREMENTS% -i %ALIYUN_MIRROR% --trusted-host mirrors.aliyun.com --timeout %PIP_TIMEOUT%
if errorlevel 1 (
    echo.
    echo [WARN] Batch install had failures. Trying required packages one by one...
    echo.
    set INSTALL_FAILED=0
    for %%p in (PyMuPDF python-docx openpyxl python-pptx pypdf) do (
        echo Installing %%p...
        python -m pip install %%p -i %ALIYUN_MIRROR% --trusted-host mirrors.aliyun.com --timeout %PIP_TIMEOUT% -q
        if errorlevel 1 (
            echo [ERROR] Failed to install %%p -- REQUIRED
            set INSTALL_FAILED=1
        ) else (
            echo [OK] %%p installed
        )
    )
    echo.
    echo Installing optional packages...
    for %%p in (pdfplumber pandas) do (
        echo Installing %%p...
        python -m pip install %%p -i %ALIYUN_MIRROR% --trusted-host mirrors.aliyun.com --timeout %PIP_TIMEOUT% -q
        if errorlevel 1 (
            echo [WARN] Failed to install %%p -- optional, will be skipped
        ) else (
            echo [OK] %%p installed
        )
    )
    if "!INSTALL_FAILED!"=="1" (
        echo.
        echo [ERROR] Some required packages failed to install.
        exit /b 1
    )
)
echo.

REM ================================================================
REM STEP 4: 验证安装结果
REM ================================================================
echo Verifying installation...
python -c "import fitz; import docx; import openpyxl; import pptx; import pypdf; print('[OK] All required packages verified')"
if errorlevel 1 (
    echo.
    echo [ERROR] Package verification failed. Some required packages are missing.
    echo         Try: %VENV_DIR%\Scripts\python.exe -m pip install PyMuPDF python-docx openpyxl python-pptx pypdf
    exit /b 1
)

python -c "import pdfplumber; import pandas; print('[OK] Optional packages verified')"
if errorlevel 1 (
    echo [WARN] Some optional packages missing -- pdfplumber/pandas. These features will be skipped.
)

echo.
echo ============ Setup Complete ============
echo.
echo Virtual environment: %VENV_DIR%
echo Activate manually:   %VENV_DIR%\Scripts\activate.bat
echo.
echo The Java app will auto-detect the venv Python at:
echo   %cd%\%VENV_DIR%\Scripts\python.exe
echo ============================================================
