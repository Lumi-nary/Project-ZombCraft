@echo off
rem Starts Project Zomboid the way the Steam launcher does (same JVM flags as ProjectZomboid64.json), with the ZombieBuddy
rem agent the player has installed. ZombieBuddy 2.3.4 and later load Java mods on PZ 42.21 by themselves, which is what a
rem player runs, so the PzCraftShim agent is no longer added unless asked for.
rem Nothing in the game folder is modified by this script. Output goes to pz-launch.log in the project root.
rem
rem   scripts\run-pz.bat                 normal: ZombieBuddy shows its approval dialog for each new/changed Java mod jar
rem   set PZ_ZB_ARGS=policy=allow-all    (development only) skip the dialog for this run, e.g. unattended tests
rem   set PZ_ZB_ARGS=config_dir=PATH     use another ZombieBuddy config folder (the private ~/.pzcraft/zb-dev with saved approvals)
rem   set PZ_USE_SHIM=1                  only for ZombieBuddy 2.3.2/2.3.3: add the PzCraftShim agent that fixes loadMods on 42.21
rem   set ZB_VERBOSITY=2                 verbose ZombieBuddy logging
rem   set PZ_SKIP_INSTALL=1              preserve the installed mod when resuming a backed-up session
setlocal
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; if (Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -and $_.CommandLine -like '*zombie.gameStates.MainScreenState*') -or $_.Name -eq 'ProjectZomboid64.exe' }) { exit 2 }"
if errorlevel 1 (
    echo PZ is already running, or its process state could not be checked. Close it before launching again.
    exit /b 1
)
rem Apply the last successful build only while PZ is stopped. This avoids replacing classes in a live game.
if not "%PZ_SKIP_INSTALL%"=="1" if exist "%~dp0..\pz-mod\build\libs\Project-ZombCraft.jar" (
    copy /y "%~dp0..\pz-mod\build\libs\Project-ZombCraft.jar" "E:\Zomboid\mods\Project-ZombCraft\42\media\java\Project-ZombCraft.jar" >nul
    if errorlevel 1 exit /b 1
    copy /y "%~dp0..\pz-mod\mod\42\mod.info" "E:\Zomboid\mods\Project-ZombCraft\42\mod.info" >nul
    if errorlevel 1 exit /b 1
)
set "PZ=E:\SteamLibrary\steamapps\common\ProjectZomboid"
set "SHIM=%~dp0..\shim\build\libs\PzCraftShim.jar"
set "LOG=%~dp0..\pz-launch.log"
set "ZBA=-agentlib:zbNative"
if defined PZ_ZB_ARGS set "ZBA=-agentlib:zbNative=%PZ_ZB_ARGS%"
set SHIMARG=
if defined PZ_USE_SHIM set SHIMARG="-javaagent:%SHIM%"

cd /d "%PZ%"
"%PZ%\jre64\bin\java.exe" %ZBA% %SHIMARG% -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xmx6072m -Dzomboid.steam=1 -Dzomboid.znetlog=1 "-Djava.library.path=win64/;." -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -XX:+UseZGC -cp "./;projectzomboid.jar" zombie.gameStates.MainScreenState -cachedir=E:\Zomboid %* >> "%LOG%" 2>&1
endlocal
