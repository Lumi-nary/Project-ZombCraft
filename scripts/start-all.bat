@echo off
rem One click: Minecraft (hidden half) + Project Zomboid with the player's ZombieBuddy (2.3.4+; set PZ_USE_SHIM=1 for older ones).
rem ZombieBuddy will show its normal approval dialog for Project-ZombCraft.jar and Viewpoint.jar the first time.
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; if (Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -and $_.CommandLine -like '*zombie.gameStates.MainScreenState*') -or $_.Name -eq 'ProjectZomboid64.exe' }) { exit 2 }"
if errorlevel 1 (
    echo PZ is already running, or its process state could not be checked. Close it before launching again.
    exit /b 1
)
rem Minecraft runs with no window (its output goes to fabric-mod\run\logs and fabric-run-live.log)
powershell.exe -NoProfile -WindowStyle Hidden -Command "Start-Process -WindowStyle Hidden -FilePath '%~dp0run-minecraft.bat'"
call "%~dp0run-pz.bat"
