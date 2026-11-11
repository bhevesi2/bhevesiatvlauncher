# One-time setup for building the launcher on a Windows PC.
#
#   irm https://raw.githubusercontent.com/bhevesi2/bhevesiatvlauncher/main/tools/setup-windows.ps1 | iex
#
# Needs: JDK 17 (already installed). Installs: Git (if missing), the Android SDK command-line
# tools, platform-tools (adb), platform 34 and build-tools 34 into %LOCALAPPDATA%\Android\Sdk,
# and clones the project to %USERPROFILE%\bhevesiatvlauncher. Safe to run again.

function Install-LauncherBuild {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'

    $repoUrl  = 'https://github.com/bhevesi2/bhevesiatvlauncher.git'
    $repoDir  = Join-Path $env:USERPROFILE 'bhevesiatvlauncher'
    $toolsZip = 'commandlinetools-win-15859902_latest.zip'
    $toolsSha = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $toolsUrl = "https://dl.google.com/android/repository/$toolsZip"

    function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

    # --- Java -------------------------------------------------------------------------------
    Step 'Java'
    $java = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $cmd = Get-Command java -ErrorAction SilentlyContinue
        if ($cmd) { $java = $cmd.Source }
    }
    if (-not $java) {
        Write-Host 'Java was not found. Install JDK 17 (for example: winget install EclipseAdoptium.Temurin.17.JDK),' -ForegroundColor Red
        Write-Host 'open a new PowerShell window and run this script again.' -ForegroundColor Red
        return
    }
    $ErrorActionPreference = 'Continue'      # java prints its version on the error stream
    $javaVersion = (& $java -version 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'version' } | Select-Object -First 1)
    $ErrorActionPreference = 'Stop'
    Write-Host "$java  ($javaVersion)"
    if ($javaVersion -notmatch '"(17|18|19|20|21)[."]') {
        Write-Host 'Warning: the build is tested with JDK 17. If the build fails, set JAVA_HOME to a JDK 17.' -ForegroundColor Yellow
    }

    # --- Git --------------------------------------------------------------------------------
    Step 'Git'
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        $gitCmd = Join-Path $env:ProgramFiles 'Git\cmd'
        if (-not (Test-Path (Join-Path $gitCmd 'git.exe'))) {
            if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
                Write-Host 'Git is missing and winget is not available. Install Git from https://git-scm.com/download/win and run this again.' -ForegroundColor Red
                return
            }
            Write-Host 'Installing Git with winget...'
            & winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements
        }
        if (Test-Path (Join-Path $gitCmd 'git.exe')) { $env:Path = "$gitCmd;$env:Path" }
    }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Host 'Git is installed but not visible in this window. Open a new PowerShell window and run this again.' -ForegroundColor Red
        return
    }
    Write-Host (& git --version)

    # --- Android SDK ------------------------------------------------------------------------
    Step 'Android SDK'
    $sdk = $env:ANDROID_HOME
    if (-not $sdk -or -not (Test-Path $sdk)) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    New-Item -ItemType Directory -Force -Path $sdk | Out-Null
    Write-Host "SDK folder: $sdk"

    $sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $sdkManager)) {
        $tmp = Join-Path $sdk '.setup-tmp'       # same drive as the SDK, so the move below is a rename
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $zip = Join-Path $tmp $toolsZip
        Write-Host "Downloading $toolsZip (about 156 MB)..."
        & curl.exe -L --fail --retry 3 -o $zip $toolsUrl
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zip)) {
            Write-Host "Download failed. Get 'Command line tools only' for Windows from https://developer.android.com/studio#command-line-tools-only," -ForegroundColor Red
            Write-Host "unzip it so that this file exists, then run this script again:" -ForegroundColor Red
            Write-Host "  $sdkManager" -ForegroundColor Red
            return
        }
        $sha = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
        if ($sha -ne $toolsSha) {
            Write-Host "The downloaded file does not match the published checksum, stopping. ($sha)" -ForegroundColor Red
            return
        }
        Write-Host 'Unpacking...'
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
        Move-Item -Path (Join-Path $tmp 'cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools\latest')
        Remove-Item -Recurse -Force $tmp
    }
    if (-not (Test-Path $sdkManager)) {
        Write-Host "sdkmanager was not found at $sdkManager" -ForegroundColor Red
        return
    }

    Write-Host 'Accepting the Android SDK licenses...'
    $yes = (1..40 | ForEach-Object { 'y' })
    $yes | & $sdkManager --licenses | Out-Null

    Write-Host 'Installing platform-tools, platform 34 and build-tools 34 (a few minutes)...'
    $yes | & $sdkManager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'

    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb) -or -not (Test-Path (Join-Path $sdk 'platforms\android-34\android.jar'))) {
        Write-Host 'The SDK packages did not install. Scroll up for the error from sdkmanager.' -ForegroundColor Red
        return
    }
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
    $env:ANDROID_HOME = $sdk

    # --- Project ----------------------------------------------------------------------------
    Step 'Project'
    if (Test-Path (Join-Path $repoDir '.git')) {
        & git -C $repoDir pull --ff-only
    } else {
        & git clone $repoUrl $repoDir
    }
    if (-not (Test-Path (Join-Path $repoDir 'gradlew.bat'))) {
        Write-Host "The project is not in $repoDir. Scroll up for the error from git." -ForegroundColor Red
        return
    }
    $sdkForGradle = $sdk -replace '\\', '/'
    [IO.File]::WriteAllText((Join-Path $repoDir 'local.properties'), "sdk.dir=$sdkForGradle`r`n")

    Step 'Done'
    Write-Host "Project:  $repoDir"
    Write-Host 'To build the newest version and put it on the TV, run:'
    Write-Host "  $repoDir\update.bat" -ForegroundColor Green
    Write-Host 'The first time, the TV asks whether to allow USB debugging from this PC: tick "Always allow" and press OK.'
}

Install-LauncherBuild
