@echo off
rem Builds the patches, patches the SoundCloud APK and installs it next to the original app.
rem Local files (APK, tools, signing key) live in the "local" folder, which is not committed.
setlocal
rem The patch names are in Russian.
chcp 65001 >nul
cd /d "%~dp0"

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "APK_IN=local\apk\soundcloud-2026.09.02-merged.apk"
set "APK_OUT=local\out\arsound-2026.09.02.apk"
set "KEYSTORE=local\sc-revanced.keystore"

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
  -e "Arsound: без рекламы" ^
  -e "Arsound: скачивание" ^
  -e "Arsound: своя музыка" ^
  -e "Arsound: мгновенные плейлисты" ^
  -e "Arsound: сеть и батарея" ^
  -e "Arsound: без дубликатов" ^
  --keystore "%KEYSTORE%" -t local\out\tmp ^
  -o "%APK_OUT%" "%APK_IN%" || exit /b 1

rem Install for the main user only, otherwise some firmwares also install a copy into a second profile.
adb install -r --user 0 "%APK_OUT%"
