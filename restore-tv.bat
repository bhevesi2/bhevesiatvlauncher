@echo off
rem Undoes everything that was switched off on the TV: the TCL / Google background apps,
rem AirPlay, and Google TV home. Afterwards the Home button opens Google TV home again.
rem Nothing was uninstalled, so this only switches things back on.
rem   restore-tv.bat                 -> TV at 192.168.2.175 (living room TCL)
rem   restore-tv.bat 192.168.2.38    -> another TV
rem   restore-tv.bat 192.168.2.175 keep   -> switch the apps back on, but keep this launcher on Home
rem Works even when the TV shows a black screen: ADB over the network still answers.
setlocal

set "TV=192.168.2.175:5555"
if not "%~1"=="" set "TV=%~1"
echo %TV% | findstr /c:":" >nul || set "TV=%TV%:5555"
set "KEEP=0"
if /i "%~2"=="keep" set "KEEP=1"

set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SDK=%ANDROID_HOME%"
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo adb was not found at %ADB%
    echo Run tools\setup-windows.ps1 first, see README.md.
    goto end
)

echo Connecting to the TV at %TV% ...
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
    goto end
)
timeout /t 2 /nobreak >nul
goto connect

:connected
echo.
echo == Switching the background apps back on
for %%P in (
    com.tcl.logkit
    com.tcl.messagebox
    com.tcl.usercenter
    com.tcl.waterfall.overseas
    com.tcl.channelplus
    com.tcl.esticker
    com.tcl.exhibit
    com.android.adservices.api
    com.android.ondevicepersonalization.services
    com.android.federatedcompute.services
    com.android.tv.feedbackconsent
    com.google.android.feedback
    com.tcl.smartalexa
    com.tcl.hearaid
    com.google.android.marvin.talkback
    com.canal.android.canal
    com.tcl.airplay2
    com.mediatek.AirplayAPK
    com.mediatek.airplaydaemon
) do (
    "%ADB%" -s %TV% shell pm enable %%P 2>nul | findstr /c:"new state" >nul && echo   on: %%P
)

echo.
echo == Drawing the menus at 1080p again (as the TV came)
rem No forced size: the TV itself limits its menus to 1920 wide and scales the density with it.
rem A forced 1920x1080 would start up at the 4K density after every restart (everything twice as big).
"%ADB%" -s %TV% shell wm size reset >nul 2>nul
"%ADB%" -s %TV% shell wm density reset >nul 2>nul

if "%KEEP%"=="1" goto done

echo.
echo == Giving the Home button back to Google TV home
"%ADB%" -s %TV% shell pm enable com.google.android.tungsten.setupwraith >nul 2>nul
"%ADB%" -s %TV% shell pm enable com.google.android.apps.tv.launcherx >nul 2>nul
"%ADB%" -s %TV% shell pm enable com.google.android.tvlauncher >nul 2>nul
"%ADB%" -s %TV% shell pm list packages com.google.android.apps.tv.launcherx | findstr /c:"launcherx" >nul
if not errorlevel 1 (
    "%ADB%" -s %TV% shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
) else (
    "%ADB%" -s %TV% shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
)
"%ADB%" -s %TV% shell input keyevent KEYCODE_HOME

:done
echo.
echo Done. Restart the TV if something still looks off.
echo To put this launcher back on the Home button later: home-button.bat launcher %TV%

:end
echo.
pause
