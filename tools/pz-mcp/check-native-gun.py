"""In-place M9 ammo/contact/cooldown/cancel fixture. Only temporary gun/magazines/target are edited."""
import json
import math
import time
import traceback
from pathlib import Path
import pzcore as p


def run():
    out = Path(__file__).parent / ('native-gun-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    before = p.state()
    assert before['paused'] and not before['input']['live'] and not before['overrideActive']
    assert before['zombies'] == 0 and before['pzMirror']['status'] == 'in step'
    inv = before['minecraftGui']['inventory']
    slot = next(i for i in range(9) if inv[i].startswith('0 '))
    option = str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    r = {'passed': False, 'before': before, 'samples': []}
    def save(): (out / 'result.json').write_text(json.dumps(r, indent=2))
    def native():
        return p.lua('local w=_pzCodexGun; return tostring(w:getID())..","..tostring(w:getCurrentAmmoCount())..","'
                     '..tostring(w:isRoundChambered())..","..tostring(w:isContainsClip())..","..tostring(w:isJammed())')['results'][0]
    def sample(name):
        s=p.state(); t={'name':name,'pz':s['pzGuns'],'mc':s['minecraftGui']['gun'],'weapon':native()};r['samples'].append(t);save();return t
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('mc pzgive Base.Pistol 1')
        p.wait_for(lambda: 'Base.Pistol' in p.state()['minecraftGui']['inventory'][slot] or None, 10,.3,'temporary M9')
        p._select_slot(slot)
        p.wait_for(lambda:p.state()['pzMirror']['status']=='in step' or None,20,.3,'mirror')
        time.sleep(1)
        made=p.lua('local a=getPlayer():getInventory():getItems(); for i=0,a:size()-1 do local w=a:get(i); '
                   'if w:getFullType()=="Base.Pistol" then _pzCodexGun=w; w:setJamGunChance(0); end end; '
                   'assert(_pzCodexGun); _pzCodexGunFixtures={}; local m=getPlayer():getInventory():AddItem(_pzCodexGun:getMagazineType()); '
                   'm:setCurrentAmmoCount(15); _pzCodexGunFixtures[m]=true; '
                   '_pzCodexNoise=0; _pzCodexNoiseFn=function(x,y,z,radius,volume,source) '
                   'if source==getPlayer() and radius==_pzCodexGun:getSoundRadius() then _pzCodexNoise=_pzCodexNoise+1 end end; '
                   'Events.OnWorldSound.Add(_pzCodexNoiseFn); return _pzCodexGun:getMagazineType()')['results'][0]
        r['magazineType']=made;save()
        # Empty primary click must not become a melee attack or wear a weapon.
        b=p.state();p.send_input(['ATTACK'],150,wait=True);time.sleep(.4); a=sample('empty primary click')
        assert a['pz']['dry']==b['pzGuns']['dry']+1 and a['pz']['shots']==b['pzGuns']['shots']
        assert p.state()['pzWeapons']['wearRolls']==b['pzWeapons']['wearRolls']
        # Cancel a pending reload by changing hotbar; no ammo/magazine mutation before completion.
        initial=native();p.cmd('mc pzgun reload')
        p.wait_for(lambda:p.state()['pzGuns']['reloading'] or None,4,.05,'reload start')
        p._select_slot(before['minecraftGui']['selectedSlot'] if before['minecraftGui']['selectedSlot']!=slot else (slot+1)%9)
        p.wait_for(lambda:not p.state()['pzGuns']['reloading'] or None,3,.1,'swap cancellation')
        assert native()==initial;sample('swap cancels reload')
        p._select_slot(slot);time.sleep(.3)
        # Exercise the actual forwarded R key, rather than only a debug command.
        t=time.monotonic();p.api('POST','/gui',{'key':82,'action':1});p.api('POST','/gui',{'key':82,'action':0})
        p.wait_for(lambda:p.state()['pzGuns']['last'].get('status')=='reloaded' or None,8,.05,'R reload finish')
        r['reloadSeconds']=time.monotonic()-t; sample('R loads native magazine and chambers')
        assert 1.5 <= r['reloadSeconds'] < 6, 'native timed reload'
        assert native().split(',')[1:4]==['14','true','true']
        # One nonlethal target positioned along the existing view direction; no teleport or owner-block edits.
        s=p.state()['steve']; yaw=math.radians(s['yaw']);x=s['x']-math.sin(yaw)*4;y=s['z']+math.cos(yaw)*4
        made=p.lua('local known={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do known[a:get(i)]=true end; '
                   f'addZombiesInOutfit({math.floor(x)},{math.floor(y)},0,1,nil,50); '
                   'a=getCell():getZombieList();local n=0;for i=0,a:size()-1 do local z=a:get(i);if not known[z] then '
                   f'z:setX({x});z:setY({y});z:setHealth(100);z:setUseless(true);_pzCodexGunTarget=z;n=n+1 end end;return n')['results'][0]
        assert float(made)==1;time.sleep(.7)
        b=p.state();p.send_input(['ATTACK'],150,wait=True);time.sleep(.15);a=sample('loaded primary click')
        assert a['pz']['shots']==b['pzGuns']['shots']+1
        assert p.state()['pzWeapons']['wearRolls']==b['pzWeapons']['wearRolls']+1
        assert native().split(',')[1:3]==['13','true']
        assert float(p.lua('return _pzCodexNoise')['results'][0])>=1
        # Back-to-back commands reach the same authority as input and cannot consume extra rounds during cooldown.
        time.sleep(1.2);b=p.state();p.cmd('mc pzgun fire');p.cmd('mc pzgun fire');time.sleep(.15);a=sample('rapid fire cooldown')
        assert a['pz']['shots']==b['pzGuns']['shots']+1 and a['pz']['rejected']>b['pzGuns']['rejected']
        assert native().split(',')[1:3]==['12','true']
        # Several shots sample native hit chance while the temporary target remains nonlethal and stationary.
        for n in range(4):
            time.sleep(1.3)
            p.lua('local z=_pzCodexGunTarget; z:setHealth(100);z:setFakeDead(false);z:setOnFloor(false);z:setKnockedDown(false)')
            p.cmd('mc pzgun fire');time.sleep(.2);sample('native shot '+str(n))
        r['targetHealth']=float(p.lua('return _pzCodexGunTarget:getHealth()')['results'][0])
        r['noiseEvents']=float(p.lua('return _pzCodexNoise')['results'][0]);r['afterShots']=p.state()
        assert r['afterShots']['pzGuns']['hits']>0 and r['afterShots']['pzGuns']['failures']==0
        assert r['noiseEvents']==r['afterShots']['pzGuns']['shots']
        r['passed']=True
    except Exception as e:
        r['error']=str(e);r['traceback']=traceback.format_exc()
    finally:
        errors=[]
        for fn in [lambda:p.cmd('inputrelease'),
                   lambda:p.lua('if _pzCodexNoiseFn then Events.OnWorldSound.Remove(_pzCodexNoiseFn);_pzCodexNoiseFn=nil end; '
                     'if _pzCodexGunTarget then _pzCodexGunTarget:removeFromWorld();_pzCodexGunTarget:removeFromSquare();_pzCodexGunTarget=nil end; '
                     'if _pzCodexGunFixtures then for i,_ in pairs(_pzCodexGunFixtures) do getPlayer():getInventory():Remove(i) end;_pzCodexGunFixtures=nil end;_pzCodexGun=nil'),
                   lambda:p._select_slot(before['minecraftGui']['selectedSlot'])]:
            try:fn()
            except Exception as e:errors.append(str(e))
        try:
            # Only clear slots that were empty initially and contain our known fixture types.
            for i,item in enumerate(p.state()['minecraftGui']['inventory']):
                if inv[i].startswith('0 ') and ('Base.Pistol' in item or '9mmClip' in item):p.cmd(f'mc item replace entity @s container.{i} with minecraft:air')
            p.wait_for(lambda:p.state()['minecraftGui']['inventory']==inv and p.state()['pzMirror']['status']=='in step' or None,25,.3,'fixture inventory restored')
        except Exception as e:errors.append(str(e))
        try:p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+')')
        except Exception as e:errors.append(str(e))
        r['after']=p.state();r['cleanupErrors']=errors
        if errors:r['passed']=False
        save()
    print(json.dumps({'folder':str(out.resolve()),'passed':r['passed'],'error':r.get('error'),'cleanupErrors':errors}))
    raise SystemExit(0 if r['passed'] else 1)


if __name__=='__main__':run()
