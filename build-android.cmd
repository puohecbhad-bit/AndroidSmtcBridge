@echo off
setlocal
if "%~1"=="" (
  echo Usage: build-android.cmd E:\BUILD_DIRECTORY
  exit /b 2
)
set "BUILDROOT=%~f1"
if /I not "%BUILDROOT:~0,2%"=="E:" if /I not "%BUILDROOT:~0,2%"=="F:" (
  echo Build directory must be on E: or F:.
  exit /b 2
)
set "GRADLE_USER_HOME=%BUILDROOT%\.gradle"
if not exist "%ANDROID_HOME%\platforms" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
pushd "%~dp0android"
call gradlew.bat --no-daemon assembleRelease
set "RESULT=%errorlevel%"
popd
exit /b %RESULT%
