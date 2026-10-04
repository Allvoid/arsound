@echo off
rem Builds the patches, patches the SoundCloud APK and installs it next to the original app.
rem Usage: build-and-install.cmd [phone or emu]. Without an argument the APK goes to every connected device.
rem Local files (APK, tools, signing key) live in the "local" folder, which is not committed.
setlocal
rem The patch names are in Russian.
chcp 65001 >nul
cd /d "%~dp0"
set "TARGET=%~1"
if not "%TARGET%"=="" if /i not "%TARGET%"=="phone" if /i not "%TARGET%"=="emu" (
  echo Unknown target "%TARGET%". Use phone, emu or nothing for every connected device.
  exit /b 1
)

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "APK_IN=local\apk\soundcloud-2026.09.02-merged.apk"
set "APK_OUT=local\out\arsound-2026.09.02.apk"
set "KEYSTORE=local\sc-revanced.keystore"
set "KEYSTORE_ARGS="
rem A keystore exported from ReVanced Manager lets the build update an app installed by Manager, keeping its data.
if exist "local\manager.keystore" (
  set "KEYSTORE=local\manager.keystore"
  rem Its alias and key password, as shown in Manager under Import and export, are kept in local files next to it.
  rem The keystore itself has no password.
  set /p KEYSTORE_ALIAS=<"local\manager.keystore.alias"
  set /p KEYSTORE_PASSWORD=<"local\manager.keystore.password"
)
if exist "local\manager.keystore" (
  set "KEYSTORE_ARGS=--keystore-entry-alias=%KEYSTORE_ALIAS% --keystore-entry-password=%KEYSTORE_PASSWORD%"
)

if not exist "%APK_IN%" (
  echo Missing %APK_IN%. See README: "Getting the SoundCloud APK".
  exit /b 1
)

rem GitHub Packages access for the ReVanced Gradle plugin (token needs read:packages).
for /f "delims=" %%t in ('gh auth token') do set "ORG_GRADLE_PROJECT_githubPackagesPassword=%%t"
for /f "delims=" %%u in ('gh api user --jq .login') do set "ORG_GRADLE_PROJECT_githubPackagesUsername=%%u"

call "%~dp0gradlew.bat" :patches:buildAndroid --console=plain || exit /b 1

rem The patches file carries the version from gradle.properties in its name.
for %%f in (patches\build\libs\patches-*.rvp) do set "RVP=%%f"

rem The "Arsound: ..." groups include every patch of this project, with the options they need.
"%JAVA_HOME%\bin\java.exe" -jar local\tools\revanced-cli-6.0.0-all.jar patch ^
  -p "%RVP%" -b --exclusive ^
  -e "Arsound: основа" ^
  -e "Arsound: без лишнего" ^
  -e "Arsound: скачивание" ^
  -e "Arsound: своя музыка" ^
  -e "Arsound: мгновенные плейлисты" ^
  -e "Arsound: сеть и батарея" ^
  -e "Arsound: без дубликатов" ^
  -e "Arsound: плеер" ^
  --keystore "%KEYSTORE%" %KEYSTORE_ARGS% -t local\out\tmp ^
  -o "%APK_OUT%" "%APK_IN%" || exit /b 1

set "ADB=adb"
if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "INSTALLED="
set "FAILED="
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do if "%%b"=="device" call :install %%a
if defined FAILED exit /b 1
if not defined INSTALLED (
  echo No connected device for this target. The APK is in %APK_OUT%.
  exit /b 1
)
exit /b 0

rem Installs the APK on one device, if it matches the target: "emu" is any emulator, "phone" is anything else.
:install
set "SERIAL=%~1"
if /i "%TARGET%"=="emu" if /i not "%SERIAL:~0,9%"=="emulator-" exit /b 0
if /i "%TARGET%"=="phone" if /i "%SERIAL:~0,9%"=="emulator-" exit /b 0
echo Installing on %SERIAL%
rem Install for the main user only, otherwise some firmwares also install a copy into a second profile.
"%ADB%" -s %SERIAL% install -r --user 0 "%APK_OUT%" || set "FAILED=1"
set "INSTALLED=1"
exit /b 0
