@echo off
rem Builds the patches, patches the SoundCloud APK and installs it next to the original app.
rem Local files (APK, tools, signing key) live in the "local" folder, which is not committed.
setlocal
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

rem Options (-O) belong to the patch enabled right before them, so "Change package name" must stay last.
"%JAVA_HOME%\bin\java.exe" -jar local\tools\revanced-cli-6.0.0-all.jar patch ^
  -p patches\build\libs\patches-0.1.0.rvp -b --exclusive ^
  -e "Settings" ^
  -e "Disable telemetry" ^
  -e "Download tracks" ^
  -e "Control playback advertisements" ^
  -e "Offline first playlists" ^
  -e "Network" ^
  -e "Play downloaded files" ^
  -e "Local music" ^
  -e "Power saving" ^
  -e "Hide duplicate recommendations" ^
  -e "Hide subscription offers" ^
  -e "Change account type" ^
  -e "Custom app name" ^
  -e "Change package name" -O "Update permissions=true" -O "Update providers=true" ^
  --keystore "%KEYSTORE%" -t local\out\tmp ^
  -o "%APK_OUT%" "%APK_IN%" || exit /b 1

rem Install for the main user only, otherwise some firmwares also install a copy into a second profile.
adb install -r --user 0 "%APK_OUT%"
