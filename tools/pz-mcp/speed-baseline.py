"""Short 0.11 flight capture before installing 0.12; only uses the backed-up feature save."""
import json,time
from pathlib import Path
import pzcore as p
out=Path(__file__).parent
s=p.state(); origin=s['player']; samples=[]
option=p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
try:
    p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    p.send_input(release=True,look={'yaw':-90,'pitch':10,'relative':False})
    p.cmd('mc gamemode creative');time.sleep(.5)
    for _ in range(2):
        p.send_input(hold=['JUMP'],ms=100,wait=True);time.sleep(.1)
    p.send_input(hold=['JUMP'],ms=3000,wait=True)
    for mode in [1,2]:
        p.cmd(f'camera {mode}');time.sleep(.5)
        p.send_input(hold=['FWD','SPRINT'],ms=6000)
        start=time.monotonic();last_shot=0
        while time.monotonic()-start<5:
            st=p.state();samples.append({'t':time.monotonic()-start,'mode':mode,'pose':st['steve'],'camera':st['viewpoint']['minecraftCamera'],'fps':st['fps']})
            if time.monotonic()-last_shot>1:
                last_shot=time.monotonic();(out/f'speed-before-{mode}-{int(time.monotonic()-start)}.png').write_bytes(p.screenshot(.65))
            time.sleep(.1)
        p.send_input(release=True)
finally:
    p.send_input(release=True); p.cmd('mc attribute @s minecraft:flying_speed base set 0.4')
    now=p.state()['player'];p.lua(f"getPlayer():setZ({origin['z']})");p.cmd(f"tp {origin['x']-now['x']} {origin['y']-now['y']}");time.sleep(3)
    p.cmd('camera 0');p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss('+option+')')
    (out/'speed-before.json').write_text(json.dumps(samples,indent=2))
print(json.dumps({'samples':len(samples),'start':samples[0]['pose'] if samples else None,'end':samples[-1]['pose'] if samples else None}))
