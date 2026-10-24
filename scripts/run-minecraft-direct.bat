@echo off
rem Prepared by fabric-mod\gradlew prepareDirectLaunch. This starts no Gradle daemon.
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
set "PZCRAFT_AUTOEXIT=1"
set "PZCRAFT_ARGS=%~dp0..\fabric-mod\build\pzcraft-direct.args"
if not exist "%PZCRAFT_ARGS%" (
    echo Prepare the runtime first: fabric-mod\gradlew.bat prepareDirectLaunch --offline
    exit /b 1
)
cd /d "%~dp0..\fabric-mod\run"
"%JAVA_HOME%\bin\java.exe" "@%PZCRAFT_ARGS%" >> "%~dp0..\fabric-run-live.log" 2>&1
