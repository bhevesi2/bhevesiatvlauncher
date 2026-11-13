@echo off
rem Chooses what the TV's Home button opens: this launcher or Google TV home.
rem   home-button.bat                       -> shows the current choice and asks
rem   home-button.bat launcher              -> this launcher
rem   home-button.bat google                -> Google TV home
rem   home-button.bat google 192.168.2.50   -> another TV
rem Works on its own: it only needs adb from the Android SDK (tools\setup-windows.ps1 installs it).
setlocal

set "TV=192.168.2.38:5555"
set "WANT=%~1"
if not "%~2"=="" set "TV=%~2"
echo %TV% | findstr /c:":" >nul || set "TV=%TV%:5555"

set "OURS=hu.otthon.launcher"
set "STOCK=com.google.android.tvlauncher"
set "OUT=%TEMP%\home-button.txt"

set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SDK=%ANDROID_HOME%"
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" goto noadb

echo Connecting to the TV at %TV% ...
echo If the TV shows "Allow USB debugging?", tick "Always allow from this computer" and press OK.
set /a TRIES=0
:connect
"%ADB%" connect %TV% >nul 2>nul
"%ADB%" -s %TV% get-state 2>nul | findstr /x /c:"device" >nul
if not errorlevel 1 goto connected
set /a TRIES+=1
if %TRIES% geq 30 goto notv
timeout /t 2 /nobreak >nul
goto connect

:connected
rem Google TV sets (Chromecast, newer TCL) have another home app, and a setup helper that
rem switches it back on, so that one has to go too.
set "STOCKACT=%STOCK%/.MainActivity"
set "SETUP="
"%ADB%" -s %TV% shell pm list packages com.google.android.apps.tv.launcherx 2>nul | findstr /c:"launcherx" >nul
if not errorlevel 1 (
    set "STOCK=com.google.android.apps.tv.launcherx"
    set "STOCKACT=com.google.android.apps.tv.launcherx/.home.HomeActivity"
    set "SETUP=com.google.android.tungsten.setupwraith"
)
call :show
if /i "%WANT%"=="launcher" goto launcher
if /i "%WANT%"=="google" goto google
echo.
echo   1  This launcher
echo   2  Google TV home
echo   Q  Leave it as it is
choice /c 12Q /n /m "Home button should open: "
if errorlevel 3 goto end
if errorlevel 2 goto google
goto launcher

:launcher
echo.
echo Switching to this launcher ...
rem Google's home app outranks every installed launcher, so it has to be switched off. It stays installed.
"%ADB%" -s %TV% shell pm disable-user %STOCK%
if defined SETUP "%ADB%" -s %TV% shell pm disable-user %SETUP%
"%ADB%" -s %TV% shell cmd package set-home-activity %OURS%/.HomeActivity
goto done

:google
echo.
echo Switching to Google TV home ...
"%ADB%" -s %TV% shell pm enable %STOCK%
if defined SETUP "%ADB%" -s %TV% shell pm enable %SETUP%
"%ADB%" -s %TV% shell cmd package set-home-activity %STOCKACT%
goto done

:done
echo.
call :show
goto end

:show
set "NOW=something else, or the TV asks"
"%ADB%" -s %TV% shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN > "%OUT%" 2>nul
findstr /c:"%OURS%/" "%OUT%" >nul 2>nul && set "NOW=This launcher"
findstr /c:"%STOCK%/" "%OUT%" >nul 2>nul && set "NOW=Google TV home"
del "%OUT%" >nul 2>nul
echo The Home button opens: %NOW%
exit /b 0

:noadb
echo adb was not found at %ADB%
echo Run tools\setup-windows.ps1 first, see README.md.
goto end

:notv
echo.
echo Could not reach the TV. Is it switched on, and is network debugging still enabled?
"%ADB%" devices
goto end

:end
echo.
pause
