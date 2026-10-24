@echo off
rem Development launch through Loom. Normal play uses run-minecraft-direct.bat.
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PZCRAFT_AUTOEXIT=1"
cd /d "%~dp0..\fabric-mod"
call .\gradlew.bat runClient --console=plain >> "%~dp0..\fabric-run-live.log" 2>&1
