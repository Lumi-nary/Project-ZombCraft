"""Correct fresh range targets to exactly 8/16/24 block centres and save both worlds."""
import json
import time
from pathlib import Path
import pzcore as p

out = Path(__file__).parent / 'testing-site-20261004-202414'
s = p.state()
assert s['paused'] and not s['overrideActive'] and s['input']['keys'] == 0
assert p.processes()['pz'] and p.processes()['minecraft']
option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
r = {'before': s, 'targetCentres': [[2321.5,14.5,6277.5],[2327.5,14.5,6285.5],[2333.5,14.5,6293.5]], 'distances': [8,16,24]}
try:
    p.lua('_pzSiteFinishHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzSiteFinishHeld,{z,z:isUseless()});z:setUseless(true) end;getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    commands=[]
    for x,z,color in [(2321,6277,'blue'),(2327,6285,'yellow'),(2333,6293,'red')]:
        commands += [f'fill {x-1} 13 {z} {x+1} 15 {z} minecraft:air',
                     f'setblock {x} 14 {z-1} minecraft:air',
                     f'fill {x-1} 13 {z+1} {x+1} 15 {z+1} minecraft:{color}_concrete',
                     f'setblock {x} 14 {z} minecraft:target']
    for c in commands: p.cmd('mc '+c)
    p.cmd('mc pzcraft_probe_clear')
    for name,x,y,z in [('target8',2321,14,6277),('target16',2327,14,6285),('target24',2333,14,6293),('spawn',2327,12,6269)]:
        p.cmd(f'mc pzcraft_probe {name} {x} {y} {z}')
    def checked():
        probes = p.state()['minecraftGui']['server']['probes']
        return probes if len(probes)==4 and all('minecraft:target' in probes.get('target'+str(n),'') for n in (8,16,24)) and 'lime_concrete' in probes.get('spawn','') else None
    r['probes'] = p.wait_for(checked,10,.15,'corrected target blocks')
    r['commands'] = commands
    p.cmd('mc pzcraft_probe_clear')
finally:
    p.send_input(release=True)
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+');if _pzSiteFinishHeld then for _,v in ipairs(_pzSiteFinishHeld) do v[1]:setUseless(v[2]) end;_pzSiteFinishHeld=nil end')
    r['after'] = p.state()
    r['inventoryPreserved'] = s['minecraftGui']['inventory'] == r['after']['minecraftGui']['inventory']
    r['healthPreserved'] = s['health']['steveHealth'] == r['after']['health']['steveHealth']
    (out/'lane-distance-fix.json').write_text(json.dumps(r,indent=2))
r['saved'] = p.save_worlds()
r['final'] = p.state()
r['cleanup'] = p.lua('return tostring(_pzSiteHeld==nil and _pzSiteFinishHeld==nil)')['results'][0]
(out/'lane-distance-fix.json').write_text(json.dumps(r,indent=2))
(out/'site-final.png').write_bytes(p.screenshot(.8))
print(json.dumps({k:r[k] for k in ('distances','probes','inventoryPreserved','healthPreserved','saved','cleanup')}))
print(json.dumps({'paused':r['final']['paused'],'steve':r['final']['steve'],'health':r['final']['health']['steveHealth'],'input':r['final']['input'],'terrain':r['final']['minecraftGui']['terrain']}))
assert r['inventoryPreserved'] and r['healthPreserved'] and r['cleanup']=='true' and 'PZ saved' in r['saved'] and 'Minecraft saved' in r['saved']
