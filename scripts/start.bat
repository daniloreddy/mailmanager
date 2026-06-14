@echo off
cd /d "%~dp0.."

if exist venv\Scripts\activate.bat (
    call venv\Scripts\activate.bat
) else if exist .venv\Scripts\activate.bat (
    call .venv\Scripts\activate.bat
)

:: Optional: set MAILMANAGER_API_KEY=your_token_here
:: Optional: set MAILMANAGER_PORT=8080
:: Optional: set SPAMASSASSIN_HOST=spamassassin

python main.py
