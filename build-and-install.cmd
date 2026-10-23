@echo off
rem Builds the patches, patches the SoundCloud APK and installs it next to the original app.
rem Usage: build-and-install.cmd [phone or emu] [china]. Without phone or emu the APK goes to every connected device.
rem "china" builds Arsound for Chinese firmware: under the package of a Chinese music player, which the control centre
rem player of vivo (and other firmwares with such a list) shows without any setup. It installs next to the usual build.
rem Local files (APK, tools, signing key) live in the "local" folder, which is not committed.
setlocal
rem The patch names are in Russian.
chcp 65001 >nul
cd /d "%~dp0"
set "TARGET="
set "VARIANT="
:arguments
if "%~1"=="" goto arguments_done
if /i "%~1"=="phone" (
  set "TARGET=phone"
) else if /i "%~1"=="emu" (
  set "TARGET=emu"
) else if /i "%~1"=="china" (
  set "VARIANT=china"
) else (
  echo Unknown argument "%~1". Use phone or emu, or nothing for every connected device, and china for the Chinese build.
  exit /b 1
)
shift
goto arguments
:arguments_done

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "APK_IN=local\apk\soundcloud-2026.10.14-merged.apk"
set "APK_OUT=local\out\arsound-2026.10.14.apk"
set "PACKAGE_ARGS="
rem KuGou Concept: on the vivo list as a plain media session app (not a "cooperate" one), and on the lists of
rem OPPO and realme firmware too. Russian-speaking users hardly ever have it, and an app cannot share a package.
if "%VARIANT%"=="china" (
  set "APK_OUT=local\out\arsound-2026.10.14-china.apk"
  set "PACKAGE_ARGS=-e "Change package name" -O "Package name=com.kugou.viper""
)
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
  %PACKAGE_ARGS% ^
  --keystore "%KEYSTORE%" %KEYSTORE_ARGS% -t local\out\tmp ^
  -o "%APK_OUT%" "%APK_IN%" > local\out\patch.log 2>&1
set "PATCH_EXIT=%errorlevel%"
type local\out\patch.log
if not "%PATCH_EXIT%"=="0" exit /b 1
rem revanced-cli still saves the APK when some patches fail; such an APK must not be installed.
findstr /c:"SEVERE:" local\out\patch.log >nul && (
  echo Some patches failed, nothing is installed. See local\out\patch.log.
  exit /b 1
)

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
