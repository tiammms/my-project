@echo off
rem Saqtan: scheme radar demo. Needs Python and the GEMINI_API_KEY environment variable (never put the key in a file).
chcp 65001 >nul
cd /d "%~dp0"
set "PY="
for %%V in (3.13 3.12 3.11 3.10) do (
  if not defined PY (
    py -%%V -c "import sys" >nul 2>nul && set "PY=py -%%V"
  )
)
if not defined PY (
  python -c "import sys; sys.exit(0 if sys.version_info[:2] >= (3,10) else 1)" >nul 2>nul && set "PY=python"
)
if not defined PY (
  echo Python 3.10 or newer not found. Install it from https://www.python.org/downloads/windows/
  echo and tick "Add python.exe to PATH". Then run this file again.
  pause
  exit /b 1
)
set "PYTHONUTF8=1"
%PY% -m scripts.radar_demo
pause
