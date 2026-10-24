@echo off
rem Multiplayer test, host side: the Minecraft (Fabric) server from the dev build, in E:\PzCraftMP\mc-server (its own world,
rem eula.txt, mods and logs). Accept the Minecraft EULA in E:\PzCraftMP\mc-server\eula.txt first (eula=true).
rem Output is appended to E:\PzCraftMP\logs\mc-server.log. Stop it by typing "stop" in its window.
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist "E:\PzCraftMP\logs" mkdir "E:\PzCraftMP\logs"
cd /d "%~dp0..\..\fabric-mod"
call .\gradlew.bat runServer --console=plain >> "E:\PzCraftMP\logs\mc-server.log" 2>&1
