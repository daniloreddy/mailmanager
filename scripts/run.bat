@echo off
cd /d "%~dp0.."

if not exist venv\Scripts\activate.bat (
    echo Creating venv...
    python -m venv venv
    venv\Scripts\pip install -r requirements.txt
    if %errorlevel% neq 0 exit /b %errorlevel%
)

call venv\Scripts\activate.bat

:: Optional: set REQUIRE_AUTH=true
:: Optional: set PORT=8080

python -m app.main %*
