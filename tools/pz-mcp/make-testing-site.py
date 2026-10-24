"""Create the owner-authorised dedicated range in an empty sky volume, then teleport there. No inventory edits or enemies."""
import json
import shutil
import time
import traceback
from pathlib import Path
import pzcore as p


def run():
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = Path(__file__).parent / ('testing-site-' + stamp)
    out.mkdir()
    state = p.state()
    assert state['paused'] and not state['overrideActive'], 'expected the current paused owner session'
    assert state['pzMirror']['status'] == 'in step'
    assert p.processes()['pz'] and p.processes()['minecraft']
    assert state['minecraftGui']['carried'].startswith('0 '), 'finish the carried inventory transfer first'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    result = {'before': state, 'bounds': [2304,11,6264,2343,17,6307], 'arrival': [2327.5,13,6269.5], 'created': False, 'teleported': False}
    (out/'result.json').write_text(json.dumps(result,indent=2))
    saved = p.save_worlds()
    assert 'PZ saved' in saved and 'Minecraft saved' in saved
    backup = p.ROOT/'.pzcraft-backups'/('before-testing-site-'+stamp)
    backup.mkdir()
    shutil.copytree(p.ZOMBOID/'Saves/Apocalypse/PzCraftFeatureTest-20261002',backup/'pz-save')
    shutil.copytree(p.ROOT/'fabric-mod/run/saves/pzcraft',backup/'mc-save',ignore=shutil.ignore_patterns('session.lock'))
    shutil.copy2(out/'result.json',backup/'before.json')
    result['backup'] = str(backup)
    print('paired saved backup completed',str(backup),flush=True)
    try:
        p.lua('_pzSiteHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzSiteHeld,{z,z:isUseless()});z:setUseless(true) end;getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('mc forceload add 2304 6264 2343 6307')
        time.sleep(.8)
        # Full-volume comparison against empty sky, not just four corner probes. Refuse to replace existing MC blocks.
        marker = 'PZCRAFT_SITE_EMPTY_' + stamp
        log = Path(p.LOGS['minecraft'])
        offset = log.stat().st_size
        p.cmd('mc execute if blocks 2304 11 6264 2343 17 6307 2304 80 6264 all run tellraw @s '+json.dumps({'text':marker}))
        def confirmed_empty():
            with log.open('rb') as f:
                f.seek(offset)
                return ('[CHAT] '+marker).encode() in f.read()
        p.wait_for(confirmed_empty,6,.1,'the entire site volume to be empty')
        result['volumeEmptyVerified'] = True
        commands = [
            'fill 2304 11 6264 2343 11 6307 minecraft:bedrock',
            'fill 2304 12 6264 2343 12 6307 minecraft:light_gray_concrete',
            'fill 2304 13 6264 2343 15 6264 minecraft:iron_bars',
            'fill 2304 13 6307 2343 15 6307 minecraft:iron_bars',
            'fill 2304 13 6265 2304 15 6306 minecraft:iron_bars',
            'fill 2343 13 6265 2343 15 6306 minecraft:iron_bars',
            'fill 2319 13 6304 2342 17 6304 minecraft:bedrock',
            'fill 2319 12 6269 2341 12 6269 minecraft:lime_concrete',
            'fill 2318 12 6265 2318 12 6303 minecraft:black_concrete',
            'fill 2307 12 6268 2315 12 6282 minecraft:orange_concrete',
            'fill 2326 12 6267 2328 12 6268 minecraft:emerald_block',
        ]
        for x,z,color in [(2321,6277,'blue'),(2327,6285,'yellow'),(2333,6293,'red')]:
            commands += [f'fill {x-2} 12 6270 {x+2} 12 6303 minecraft:white_concrete',
                         f'fill {x-2} 12 {z} {x+2} 12 {z} minecraft:{color}_concrete',
                         f'fill {x-1} 13 {z+1} {x+1} 15 {z+1} minecraft:{color}_concrete',
                         f'setblock {x} 14 {z} minecraft:target']
        for x in (2304,2343):
            for z in (6264,6276,6288,6300,6307):
                commands += [f'fill {x} 13 {z} {x} 16 {z} minecraft:polished_blackstone',f'setblock {x} 16 {z} minecraft:sea_lantern']
        for x,z in [(2308,6266),(2314,6284),(2321,6268),(2333,6268),(2321,6302),(2333,6302)]:
            commands.append(f'setblock {x} 12 {z} minecraft:sea_lantern')
        for command in commands: p.cmd('mc '+command)
        p.cmd('mc pzcraft_probe_clear')
        for name,x,y,z in [('spawn',2327,12,6269),('practice',2311,12,6275),('target8',2321,14,6277),('target16',2327,14,6285),('target24',2333,14,6293),('backstop',2327,14,6304),('rail',2304,14,6270)]:
            p.cmd(f'mc pzcraft_probe {name} {x} {y} {z}')
        def built():
            probes = p.state()['minecraftGui']['server']['probes']
            return probes if len(probes)==7 and 'lime_concrete' in probes.get('spawn','') and all('minecraft:target' in probes.get('target'+str(n),'') for n in (8,16,24)) and 'bedrock' in probes.get('backstop','') else None
        result['probes'] = p.wait_for(built,10,.15,'range blocks placed')
        result['created'] = True
        result['commands'] = commands
        (out/'result.json').write_text(json.dumps(result,indent=2))
        p.cmd('mc tp @s 2327.5 13.0 6269.5')
        p.cmd('look 0 0')
        def arrived():
            s=p.state();v=s['steve']
            return s if abs(v['x']-2327.5)<.15 and abs(v['y']-13)<.1 and abs(v['z']-6269.5)<.15 and v['onGround'] and not s['minecraftGui']['terrain']['loading'] and s['player']['hasSquare'] else None
        result['arrivalState'] = p.wait_for(arrived,35,.3,'streamed safe on-ground arrival')
        result['teleported'] = True
        p.cmd('mc forceload remove 2304 6264 2343 6307')
        p.cmd('mc pzcraft_probe_clear')
    except Exception as e:
        result['error'] = repr(e)
        result['traceback'] = traceback.format_exc()
    finally:
        p.send_input(release=True)
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+');if _pzSiteHeld then for _,v in ipairs(_pzSiteHeld) do v[1]:setUseless(v[2]) end;_pzSiteHeld=nil end')
        result['after'] = p.state()
        result['inventoryPreserved'] = result['after']['minecraftGui']['inventory'] == state['minecraftGui']['inventory']
        result['healthPreserved'] = result['after']['health']['steveHealth'] == state['health']['steveHealth']
        (out/'result.json').write_text(json.dumps(result,indent=2))
        (out/'site.png').write_bytes(p.screenshot(.8))
    if result['teleported']:
        result['saved'] = p.save_worlds()
        result['final'] = p.state()
        (out/'result.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({k:result.get(k) for k in ('created','teleported','inventoryPreserved','healthPreserved','saved','error','backup')}),flush=True)
    print('evidence',str(out.resolve()),flush=True)
    return result


if __name__=='__main__':
    r=run()
    raise SystemExit(0 if r['teleported'] and r['inventoryPreserved'] and r['healthPreserved'] else 1)
