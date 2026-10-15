@echo off
rem NeuroShield: install dependencies and start API + dashboard on http://127.0.0.1:8000 (Windows)
rem Needs Python 3.11, 3.12 or 3.13 (64-bit). Newer Python versions may lack ready-made packages.
cd /d "%~dp0"
set "PY="

rem 1) Python launcher: prefer a supported version even if a newer one is installed
for %%V in (3.13 3.12 3.11) do (
  if not defined PY (
    py -%%V -c "import sys" >nul 2>nul && set "PY=py -%%V"
  )
)

rem 2) plain "python" if it is a supported version
if not defined PY (
  python -c "import sys; sys.exit(0 if (3,11) <= sys.version_info[:2] <= (3,13) else 1)" >nul 2>nul && set "PY=python"
)

if not defined PY (
  echo.
  echo ============================================================
  echo  Python 3.11 - 3.13 not found.
  where python >nul 2>nul && python --version
  echo  Easiest: run   py install 3.13   then run this file again.
  echo  Or install Python 3.13 64-bit manually:
  echo    https://www.python.org/downloads/windows/
  echo    pick the latest Python 3.13.x, "Windows installer 64-bit"
  echo    and tick "Add python.exe to PATH" in the installer.
  echo  Your newer Python can stay installed - this script will pick 3.13.
  echo  Then run run_windows.bat again.
  echo ============================================================
  pause
  exit /b 1
)

echo Using: %PY%
%PY% --version

rem private environment so the system Python is not touched
if not exist ".venv\Scripts\python.exe" (
  %PY% -m venv .venv || (echo Could not create virtual environment & pause & exit /b 1)
)
set "VPY=.venv\Scripts\python.exe"

"%VPY%" -m pip install --upgrade pip >nul
rem binary packages only: never try to compile from source
"%VPY%" -m pip install --only-binary=:all: -r requirements.txt || (echo pip install failed - send a screenshot of this window & pause & exit /b 1)

start "" http://127.0.0.1:8000
"%VPY%" -m backend.app.main
pause
