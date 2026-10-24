# Drives a RUNNING Minecraft (scripts\run-minecraft.ps1) with a fake PZ: floor, wall, strafe, jump, fall.
# Exit code 0 = all checks passed.
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location (Join-Path $PSScriptRoot '..\pz-mod')
.\gradlew.bat build -q
java -cp build\libs\Project-ZombCraft.jar pzcraft.pz.DevHarness walk
$code = $LASTEXITCODE
Pop-Location
exit $code
