# Office / non-Maple PC environment for PzCraft development (no PZ install, no game runs).
# Dot-source it:   . .\scripts\office-env.ps1
#
# What works here: edit source, protocol tests, Fabric compile, tools/vic-guns (build.py, geoview.py).
# What does not: anything that needs PZ running (tests, restart-all, pzctl) and the PZ jar build,
# unless projectzomboid.jar + ZombieBuddy.jar are copied from the Maple PC (see PZ_JARS below).

$jdk = 'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot'
if (-not (Test-Path $jdk)) { Write-Warning "JDK 25 not found at $jdk (winget install Microsoft.OpenJDK.25)" }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

# The tools read private assets from ~/.pzcraft. On this PC that is a junction to <workspace>\.pzcraft.
$ws = Split-Path -Parent $PSScriptRoot
$link = Join-Path $env:USERPROFILE '.pzcraft'
if (-not (Test-Path $link)) { New-Item -ItemType Junction -Path $link -Target (Join-Path $ws '.pzcraft') | Out-Null }

# Optional: folder holding projectzomboid.jar and ZombieBuddy.jar copied from the Maple PC's PZ install.
# Then build the PZ mod with:  cd pz-mod; .\gradlew.bat build -PpzDir=$env:PZCRAFT_PZDIR
$env:PZCRAFT_PZDIR = $(if ($env:PZCRAFT_PZDIR) { $env:PZCRAFT_PZDIR } else { Join-Path $ws '.pz-jars' })

function pz-test    { Push-Location (Join-Path $ws 'protocol');   try { .\gradlew.bat test --console=plain } finally { Pop-Location } }
function pz-build   { Push-Location (Join-Path $ws 'pz-mod');     try { .\gradlew.bat jar "-PpzDir=$($env:PZCRAFT_PZDIR.Replace('\','/'))" --console=plain } finally { Pop-Location } }
function pz-fabric { Push-Location (Join-Path $ws 'fabric-mod'); try { .\gradlew.bat compileJava --console=plain } finally { Pop-Location } }
Write-Host "PzCraft office env: JDK $(& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1); commands: pz-test, pz-fabric, pz-build"

