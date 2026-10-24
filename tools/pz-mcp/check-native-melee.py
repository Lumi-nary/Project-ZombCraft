"""Real native melee contacts, client/server range guards and restored equipment context.
Uses an empty hotbar slot and one frozen, nonlethal zombie in place. No owner item wear or teleport.
"""
import json
import math
import time
import traceback
from pathlib import Path
import pzcore as p


def run():
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = Path(__file__).parent / f'native-melee-{stamp}'
    out.mkdir()
    before = p.state()
    assert before['paused'] and not before['input']['live'] and not before['overrideActive']
    assert before['zombies'] == 0 and before['pzMirror']['status'] == 'in step'
    inv = before['minecraftGui']['inventory']
    slot = next(i for i in range(9) if inv[i].startswith('0 '))
    original_slot = before['minecraftGui']['selectedSlot']
    steve = before['steve']
    yaw = math.radians(steve['yaw'])
    context = ('local p=getPlayer(); local function id(w) return w and (w:getFullType()..":"..w:getID()) or "nil" end; '
               'return id(p:getPrimaryHandItem())..","..id(p:getSecondaryHandItem())..","..tostring(p:isCriticalHit())'
               '..","..tostring(p:isDoShove())..","..tostring(p:isAimAtFloor())')
    result = {'passed': False, 'before': before, 'samples': [], 'contextBefore': p.lua(context)['results'][0]}
    option = str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    given_at = None
    def save():
        (out / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    def locate(distance):
        x, y = steve['x']-math.sin(yaw)*distance, steve['z']+math.cos(yaw)*distance
        p.lua(f'local z=_pzCodexMeleeTarget; z:setX({x}); z:setY({y}); z:setHealth(100); z:setUseless(true); '
              'z:setFakeDead(false); z:setOnFloor(false); z:setKnockedDown(false)')
        time.sleep(.25)
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('mc pzgive Base.Hammer 1')
        p.wait_for(lambda:p.state()['minecraftGui']['inventory'] != inv or None, 8, .3, 'hammer')
        now = p.state()['minecraftGui']['inventory']
        given_at = next(i for i in range(36) if now[i] != inv[i])
        assert now[given_at].find('Base.Hammer') >= 0 and inv[given_at].startswith('0 ')
        if given_at != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{given_at}')
            p.cmd(f'mc item replace entity @s container.{given_at} with minecraft:air')
            given_at = slot
        p._select_slot(slot)
        p.wait_for(lambda:p.state()['pzMirror']['status']=='in step' or None, 20, .3, 'mirror')
        time.sleep(1)
        x,y=steve['x']-math.sin(yaw)*.9,steve['z']+math.cos(yaw)*.9
        created=p.lua('local known={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do known[a:get(i)]=true end; '
                      f'addZombiesInOutfit({math.floor(x)},{math.floor(y)},0,1,nil,50); '
                      'a=getCell():getZombieList(); local n=0; for i=0,a:size()-1 do local z=a:get(i); if not known[z] then '
                      f'z:setX({x}); z:setY({y}); z:setHealth(100); z:setUseless(true); _pzCodexMeleeTarget=z; n=n+1 end end; return n')['results'][0]
        assert float(created)==1
        time.sleep(1)
        baseline=p.state()['pzWeapons']
        for distance,label in [(2.5,'Minecraft range guard'),(1.35,'PZ authoritative range guard')]:
            locate(distance)
            old=p.state()
            p.cmd('mc damage @e[type=minecraft:zombie,limit=1,sort=nearest,distance=..4] 1 minecraft:player_attack by @s')
            time.sleep(.6)
            new=p.state()
            health=float(p.lua('return _pzCodexMeleeTarget:getHealth()')['results'][0])
            result['samples'].append({'test':label,'distance':distance,'before':old['pzWeapons'],'after':new['pzWeapons'],'health':health,'combat':new['minecraftGui']['combat']});save()
            assert health == 100 and new['pzWeapons']['wearRolls']==old['pzWeapons']['wearRolls'], label
        assert p.state()['pzWeapons']['meleeRangeRejected']>baseline['meleeRangeRejected']
        for crit_setting in (90,0):
            p.lua('local a=getPlayer():getInventory():getItems(); for i=0,a:size()-1 do local w=a:get(i); '
                  f'if w:getFullType()=="Base.Hammer" then w:setCriticalChance({crit_setting}); end end')
            for i in range(8):
                for attempt in range(3):
                    locate(.9)
                    old=p.state()['pzWeapons']
                    crawling=str(p.lua('return _pzCodexMeleeTarget:isCrawling()')['results'][0]).lower()=='true'
                    if crawling:
                        # Native critical hits can turn this temporary zombie into a crawler. A horizontal eye-level
                        # swing legitimately misses its low body; use a server contact for further damage/wear samples.
                        p.cmd('mc damage @e[type=minecraft:zombie,limit=1,sort=nearest,distance=..4] 1 minecraft:player_attack by @s')
                    else:
                        p.send_input(['ATTACK'],150,wait=True)
                    time.sleep(1.05)
                    new=p.state()['pzWeapons']
                    if new['meleeApplied']>old['meleeApplied']:break
                    result.setdefault('misses',[]).append({'attempt':attempt,'state':p.state(),
                        'target':p.lua('local z=_pzCodexMeleeTarget; return tostring(z:isFakeDead())..","..tostring(z:isOnFloor())..","..tostring(z:isDead())')['results'][0]})
                    save()
                health=float(p.lua('return _pzCodexMeleeTarget:getHealth()')['results'][0])
                result['samples'].append({'test':'real swing','source':'server contact on crawler' if crawling else 'attack input',
                    'critSetting':crit_setting,'before':old,'after':new,'health':health});save()
                assert new['meleeApplied']==old['meleeApplied']+1 and new['wearRolls']==old['wearRolls']+1, 'one landed swing / one native wear roll'
                assert 0 < health < 100
        swings=[s['after']['lastMelee'] for s in result['samples'] if s['test']=='real swing']
        assert any(s.get('source')=='attack input' for s in result['samples']), 'at least one real input-driven contact'
        assert any(s['critical'] for s in swings) and any(not s['critical'] for s in swings)
        assert all(s['itemId']==swings[0]['itemId'] and s['weapon']=='Base.Hammer' for s in swings)
        modifier=float(p.lua('local a=getPlayer():getInventory():getItems(); for i=0,a:size()-1 do local w=a:get(i); '
                             'if w:getFullType()=="Base.Hammer" then return w:getDamageMod(getPlayer())*getPlayer():getHittingMod()'
                             '*getPlayer():getCharacterTraits():getTraitDamageDealtReductionModifier() end end')['results'][0])
        assert all(abs(s['modifier']-modifier)<1e-5 for s in swings)
        result['nativeModifier']=modifier
        result['contextAfter']=p.lua(context)['results'][0]
        assert result['contextAfter']==result['contextBefore']
        result['passed']=True
    except Exception as e:
        result['error']=f'{type(e).__name__}: {e}'
        result['traceback']=traceback.format_exc()
    finally:
        cleanup=[]
        try:
            p.cmd('inputrelease')
            p.lua('if _pzCodexMeleeTarget then _pzCodexMeleeTarget:removeFromWorld(); _pzCodexMeleeTarget:removeFromSquare(); _pzCodexMeleeTarget=nil end')
            if given_at is not None:p.cmd(f'mc item replace entity @s container.{given_at} with minecraft:air')
            p._select_slot(original_slot)
            p.wait_for(lambda:p.state()['pzMirror']['status']=='in step' or None,20,.3,'cleanup mirror')
            p.lua(f'getCore():setOptionPauseOnFocusloss({option}); UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
            result['after']=p.state()
            assert result['after']['minecraftGui']['inventory']==inv
            assert result['after']['paused'] and not result['after']['input']['live'] and result['after']['zombies']==0
        except Exception as e:cleanup.append(str(e))
        result['cleanupErrors']=cleanup
        if cleanup:result['passed']=False
        save()
    print(json.dumps({'folder':str(out.resolve()),'passed':result['passed'],'error':result.get('error'),'cleanupErrors':result['cleanupErrors']}))


if __name__=='__main__':run()
