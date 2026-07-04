@echo off
cd /d "%~dp0.."

if not exist venv\Scripts\activate.bat (
    echo Creating venv...
    python -m venv venv
    venv\Scripts\pip install -r requirements.txt
    if %errorlevel% neq 0 exit /b %errorlevel%
)

call venv\Scripts\activate.bat

:: Optional: set MAILMANAGER_API_KEY=your_token_here
:: Optional: set MAILMANAGER_PORT=8080

python main.py
