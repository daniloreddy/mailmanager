@echo off
if exist venv\Scripts\activate.bat (
    call venv\Scripts\activate.bat
) else if exist .venv\Scripts\activate.bat (
    call .venv\Scripts\activate.bat
)

echo Running Ruff check...
ruff check .
if %errorlevel% neq 0 exit /b %errorlevel%

echo Running Ruff format...
ruff format .
if %errorlevel% neq 0 exit /b %errorlevel%

echo Running Mypy...
mypy .
if %errorlevel% neq 0 exit /b %errorlevel%
