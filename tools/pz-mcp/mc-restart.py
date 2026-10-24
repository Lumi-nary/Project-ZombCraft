"""Stop and start only the hidden Minecraft half with a rebuilt Fabric jar (PZ keeps running): saves first, rebuilds, restarts,
re-pauses. python mc-restart.py   Prints the new jar identity."""
import hashlib, json, os, subprocess, time
from pathlib import Path
import pzcore as p
k = p.kill('minecraft'); print('kill:', json.dumps(k))
if k.get('refused'): raise SystemExit('refused')
env = dict(os.environ, JAVA_HOME=r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot')
r = subprocess.run(r'fabric-mod\gradlew.bat -p fabric-mod build prepareDirectLaunch --offline --no-configuration-cache --console=plain', shell=True, cwd=p.ROOT, env=env, capture_output=True, text=True)
print('build exit', r.returncode)
if r.returncode: print(r.stdout[-1500:]); raise SystemExit(1)
jar = p.ROOT / 'fabric-mod/build/libs/Project-ZombCraft-Fabric-0.13.0.jar'
print('fabric jar', hashlib.sha256(jar.read_bytes()).hexdigest(), jar.stat().st_size)
res = p.start_minecraft(); res.pop('state', None); print('start:', json.dumps(res))
p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)'); time.sleep(3)
s = p.state(); print('paused', s['paused'], '| mirror', s['pzMirror']['status'], '| view3d', s['viewpoint']['view3d'], '| slot', s['minecraftGui']['selectedSlot'])
