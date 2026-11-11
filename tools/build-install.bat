@echo off
rem Builds the release APK and installs it on the TV over ADB. Called by update.bat.
setlocal
cd /d "%~dp0.."

set "TV=192.168.2.38:5555"
set "INSTALL=1"
if /i "%~1"=="build" set "INSTALL=0"
if not "%~1"=="" if /i not "%~1"=="build" set "TV=%~1"
echo %TV% | findstr /c:":" >nul || set "TV=%TV%:5555"

set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SDK=%ANDROID_HOME%"
set "ADB=%SDK%\platform-tools\adb.exe"
set "APK=app\build\outputs\apk\release\app-release.apk"

if not exist local.properties (
    echo local.properties is missing. Run tools\setup-windows.ps1 first, see README.md.
    exit /b 1
)

echo == Building
call gradlew.bat --console=plain :app:assembleRelease
if errorlevel 1 (
    echo.
    echo BUILD FAILED. The first lines marked "e:" or "FAILURE" above say why.
    exit /b 1
)
if not exist "%APK%" (
    echo The APK was not created: %APK%
    exit /b 1
)
echo.
echo Built: %CD%\%APK%
if "%INSTALL%"=="0" exit /b 0

if not exist "%ADB%" (
    echo adb was not found at %ADB%. Run tools\setup-windows.ps1 first.
    exit /b 1
)

echo.
echo == Connecting to the TV at %TV%
echo If the TV shows "Allow USB debugging?", tick "Always allow from this computer" and press OK.
set /a TRIES=0
:connect
"%ADB%" connect %TV% >nul 2>nul
"%ADB%" -s %TV% get-state 2>nul | findstr /x /c:"device" >nul
if not errorlevel 1 goto connected
set /a TRIES+=1
if %TRIES% geq 30 (
    echo.
    echo Could not reach the TV. Is it switched on, and is network debugging still enabled?
    "%ADB%" devices
    exit /b 1
)
timeout /t 2 /nobreak >nul
goto connect

:connected
echo == Installing
"%ADB%" -s %TV% install -r "%APK%"
if errorlevel 1 (
    echo.
    echo INSTALL FAILED.
    exit /b 1
)
"%ADB%" -s %TV% shell am start -n hu.otthon.launcher/.HomeActivity >nul
echo.
echo Done. The launcher is open on the TV.
exit /b 0
