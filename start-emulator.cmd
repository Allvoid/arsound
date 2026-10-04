@echo off
rem Starts the Android emulator "arsound" and waits until Android has booted.
rem The emulator is created once, see BUILDING.md, section "Эмулятор".
setlocal
chcp 65001 >nul
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

"%ADB%" devices | findstr /b "emulator-" >nul && (
  echo The emulator is already running.
  exit /b 0
)
start "" "%ANDROID_HOME%\emulator\emulator.exe" -avd arsound -no-boot-anim
echo Waiting for Android to boot...
set /a TRIES=0
:wait
rem A 3-second pause. "timeout" fails without a console window and can be shadowed by Git's Unix "timeout".
ping -n 4 127.0.0.1 >nul
set /a TRIES+=1
set "BOOTED="
for /f %%s in ('"%ADB%" -s emulator-5554 shell getprop sys.boot_completed 2^>nul') do set "BOOTED=%%s"
if "%BOOTED%"=="1" (
  echo The emulator is ready: emulator-5554.
  exit /b 0
)
if %TRIES% lss 100 goto wait
echo The emulator did not boot in 5 minutes.
exit /b 1
