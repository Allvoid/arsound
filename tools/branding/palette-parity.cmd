@echo off
rem Checks the palette engine of the user's themes (extension theme/PaletteEngine.java) against themes.json: for cobalt,
rem mint, sakura and lime it must give exactly the tokens, colors and decoration the Python script made (see PaletteParity.java).
rem No Android and no Gradle needed, only a JDK.
setlocal
cd /d "%~dp0\..\.."
set "SIGN_JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if exist "%SIGN_JDK%\bin\javac.exe" set "JAVA_HOME=%SIGN_JDK%"
if not defined JAVA_HOME (set "JAVAC=javac" & set "JAVA=java") else (set "JAVAC=%JAVA_HOME%\bin\javac" & set "JAVA=%JAVA_HOME%\bin\java")
set "OUT=%TEMP%\arsound-palette-parity"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
"%JAVAC%" -encoding UTF-8 -d "%OUT%" extensions\arsound\src\main\java\app\revanced\extension\soundcloud\theme\PaletteEngine.java tools\branding\PaletteParity.java || exit /b 1
"%JAVA%" -cp "%OUT%" PaletteParity patches\src\main\resources\soundcloud\theme\themes.json
exit /b %ERRORLEVEL%
