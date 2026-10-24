# Starts the hidden-Minecraft half (dev client, Fabric + PzCraft) and leaves it running.
# It opens straight into the void world "pzcraft" and waits for PZ to place Steve.
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location (Join-Path $PSScriptRoot '..\fabric-mod')
.\gradlew.bat runClient
