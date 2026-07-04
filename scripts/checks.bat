@echo off
cd /d "%~dp0.."

if not exist venv\Scripts\activate.bat (
    echo Creating venv...
    python -m venv venv
    venv\Scripts\pip install -r requirements.txt -r requirements.dev.txt
    if %errorlevel% neq 0 exit /b %errorlevel%
)

call venv\Scripts\activate.bat

echo Running Ruff check...
ruff check .
if %errorlevel% neq 0 exit /b %errorlevel%

echo Running Ruff format...
ruff format .
if %errorlevel% neq 0 exit /b %errorlevel%

echo Running Mypy...
mypy .
if %errorlevel% neq 0 exit /b %errorlevel%

echo Running Pytest...
pytest tests/
if %errorlevel% neq 0 exit /b %errorlevel%
