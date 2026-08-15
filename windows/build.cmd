@echo off
setlocal
if "%~1"=="" (
  echo Usage: build.cmd OUTPUT_DIRECTORY
  exit /b 2
)
set "OUT=%~f1"
if /I not "%OUT:~0,2%"=="E:" if /I not "%OUT:~0,2%"=="F:" (
  echo Output directory must be on E: or F:.
  exit /b 2
)
if not exist "%OUT%" mkdir "%OUT%"
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSROOT=%%i"
if not defined VSROOT for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do set "VSROOT=%%i"
if not defined VSROOT if exist "%ProgramFiles(x86)%\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat" set "VSROOT=%ProgramFiles(x86)%\Microsoft Visual Studio\18\BuildTools"
if not defined VSROOT (
  echo Visual C++ Build Tools were not found.
  exit /b 1
)
call "%VSROOT%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
cl /nologo /std:c++20 /EHsc /O2 /utf-8 /DUNICODE /D_UNICODE /permissive- /Fo"%OUT%\smtc-bridge.obj" /Fe"%OUT%\smtc-bridge.exe" "%~dp0src\main.cpp" /link /SUBSYSTEM:CONSOLE
exit /b %errorlevel%
