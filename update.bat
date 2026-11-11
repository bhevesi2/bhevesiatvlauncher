@echo off
rem Gets the newest source, builds the launcher and installs it on the TV.
rem   update.bat                  -> TV at 192.168.2.38:5555
rem   update.bat 192.168.2.50     -> another address
rem   update.bat build            -> build only, no install
rem Keep this file tiny: git may rewrite the other script while this one runs.
cd /d "%~dp0"
where git >nul 2>nul || set "PATH=%ProgramFiles%\Git\cmd;%PATH%"
git pull --ff-only
if errorlevel 1 echo. & echo Could not get the newest source, building what is here. & echo.
call "%~dp0tools\build-install.bat" %*
echo.
pause
