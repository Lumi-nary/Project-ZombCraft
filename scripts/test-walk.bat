@echo off
rem Drives a RUNNING Minecraft (run-minecraft.bat) with a fake PZ: floor, wall, strafe, jump, fall. Exit code 0 = pass.
rem Close PZ first: the harness takes PZ's place on the link.
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0..\pz-mod"
call .\gradlew.bat build -q
java -cp build\libs\Project-ZombCraft.jar pzcraft.pz.DevHarness walk
set CODE=%ERRORLEVEL%
popd
exit /b %CODE%
