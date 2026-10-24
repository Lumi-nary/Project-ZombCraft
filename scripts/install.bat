@echo off
rem Builds everything and installs the PZ mod into E:\Zomboid\mods (and writes the launcher config next to it).
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0..\shim" && call .\gradlew.bat build -q && popd
pushd "%~dp0..\fabric-mod" && call .\gradlew.bat build prepareDirectLaunch --no-configuration-cache -q && popd
pushd "%~dp0..\pz-mod" && call .\gradlew.bat installMod -q && popd
echo done
