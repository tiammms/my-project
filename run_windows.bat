@echo off
rem NeuroShield: install dependencies and start API + dashboard on http://127.0.0.1:8000 (Windows)
cd /d "%~dp0"
where python >nul 2>nul || (echo Python 3.11+ is required: https://www.python.org/downloads/ & pause & exit /b 1)
python -m pip install -r requirements.txt || (echo pip install failed & pause & exit /b 1)
start "" http://127.0.0.1:8000
python -m backend.app.main
pause
