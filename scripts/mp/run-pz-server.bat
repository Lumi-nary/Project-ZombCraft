@echo off
rem Multiplayer test, host side: the Project Zomboid dedicated server, with its own cache folder (C:\Users\Maple\.pzcraft\mp\pz-server-cache),
rem so it never touches the player's E:\Zomboid. Run this and run-mc-server.bat on the host, then join with a player.
rem   scripts\mp\run-pz-server.bat                  ZombieBuddy asks on this window before loading PzCraft and Viewpoint
rem   set PZ_ZB_ARGS=policy=allow-all,config_dir=PATH   (development only: no approval prompt, for unattended tests)
rem   set PZ_TEST_ADMIN=...                         the server's admin password, only needed the first time (not stored here)
rem Output is appended to E:\PzCraftMP\logs\pz-server.log.
setlocal
set "PZ=E:\SteamLibrary\steamapps\common\ProjectZomboid"
set "CACHE=C:\Users\Maple\.pzcraft\mp\pz-server-cache"
set "LOG=E:\PzCraftMP\logs\pz-server.log"
if not exist "E:\PzCraftMP\logs" mkdir "E:\PzCraftMP\logs"
set "ZBA=-agentlib:zbNative"
if defined PZ_ZB_ARGS set "ZBA=-agentlib:zbNative=%PZ_ZB_ARGS%"
set "ADMIN="
if defined PZ_TEST_ADMIN set "ADMIN=-adminpassword %PZ_TEST_ADMIN%"
cd /d "%PZ%"
"%PZ%\jre64\bin\java.exe" %ZBA% -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xmx2048m -XX:+UseZGC -Djava.library.path=win64/;. -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -cp "./;projectzomboid.jar" zombie.network.GameServer -cachedir=%CACHE% -servername pzcraft-test -ip 127.0.0.1 %ADMIN% >> "%LOG%" 2>&1
endlocal
