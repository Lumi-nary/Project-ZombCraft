"""Shoot one temporary native window, then empty the temporary M9 into clear sky; preserve owner blocks/items."""
import json
import math
import time
import traceback
from pathlib import Path
import pzcore as p


def run():
    out=Path(__file__).parent/('gun-window-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir()
    s=p.state();assert s['paused'] and not s['input']['live'] and not s['overrideActive']
    inv=s['minecraftGui']['inventory'];slot=next(i for i in range(9) if inv[i].startswith('0 '))
    option=str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    r={'passed':False,'before':s}; errors=[]
    try:
        r['frozen']=p.lua('_pzWindowFrozen={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);'
                         'assert(z:DistTo(getPlayer())>6);_pzWindowFrozen[z]=z:isUseless();z:setUseless(true);end;return a:size()')['results'][0]
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('mc pzgive Base.Pistol 1');p.wait_for(lambda:'Base.Pistol' in p.state()['minecraftGui']['inventory'][slot] or None,10,.2,'gun')
        p._select_slot(slot);time.sleep(1)
        p.lua('local a=getPlayer():getInventory():getItems();for i=0,a:size()-1 do local w=a:get(i);if w:getFullType()=="Base.Pistol" then '
              '_pzWindowGun=w;w:setCurrentAmmoCount(2);w:setRoundChambered(true);w:setContainsClip(true);w:setJamGunChance(0);end end')
        x=math.floor(s['steve']['x'])+3;y=math.floor(s['steve']['z'])
        r['tile']=[x,y]
        # The west-facing sprite already has the correct properties; no wall on this test edge.
        r['objectCountBefore']=float(p.lua(f'local sq=getCell():getGridSquare({x},{y},0);assert(sq and not sq:getWall(false)); '
                                          '_pzWindowSquare=sq;return sq:getObjects():size()')['results'][0])
        p.lua('local w=IsoWindow.new(getCell(),_pzWindowSquare,getSprite("fixtures_windows_01_0"),false);'
              '_pzWindowSquare:AddSpecialObject(w);_pzWindowFixture=w')
        time.sleep(2);(out/'before-shot.png').write_bytes(p.screenshot(.75))
        b=p.state();p.send_input(['ATTACK'],150,wait=True);time.sleep(.4)
        a=p.state();r['afterWindowShot']=a;r['destroyed']=p.lua('return _pzWindowFixture:isDestroyed()')['results'][0]
        assert str(r['destroyed']).lower()=='true' and a['pzGuns']['windows']==b['pzGuns']['windows']+1
        (out/'after-shot.png').write_bytes(p.screenshot(.75))
        # Aim away from all native and owner-built geometry before testing the two remaining rounds.
        p.cmd('look '+str(s['steve']['yaw'])+' -75');p.send_input([],100,wait=True)
        for _ in range(2):
            time.sleep(1.3);p.send_input(['ATTACK'],150,wait=True)
        time.sleep(1.3);b=p.state();p.send_input(['ATTACK'],150,wait=True);time.sleep(.2);a=p.state()
        r['emptyShot']=a
        assert a['pzGuns']['dry']==b['pzGuns']['dry']+1 and a['pzGuns']['shots']==b['pzGuns']['shots']
        assert p.lua('return tostring(_pzWindowGun:getCurrentAmmoCount())..","..tostring(_pzWindowGun:isRoundChambered())')['results'][0]=='0,false'
        assert a['pzGuns']['failures']==0
        r['passed']=True
    except Exception as e:r['error']=str(e);r['traceback']=traceback.format_exc()
    finally:
        for f in [lambda:p.cmd('inputrelease'),
                  lambda:p.lua('if _pzWindowFixture then _pzWindowSquare:RemoveTileObject(_pzWindowFixture);_pzWindowFixture=nil end;_pzWindowGun=nil'),
                  lambda:p.cmd(f'mc item replace entity @s container.{slot} with minecraft:air'),
                  lambda:p._select_slot(s['minecraftGui']['selectedSlot']),
                  lambda:p.cmd('look '+str(s['steve']['yaw'])+' '+str(s['steve']['pitch'])),
                  lambda:p.send_input([],100,wait=True),
                  lambda:p.cmd('inputrelease'),
                  lambda:p.wait_for(lambda:p.state()['minecraftGui']['inventory']==inv and p.state()['pzMirror']['status']=='in step' or None,20,.3,'inventory cleanup'),
                  lambda:p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+')')]:
            try:f()
            except Exception as e:errors.append(str(e))
        try:
            p.lua('if _pzWindowFrozen then for z,u in pairs(_pzWindowFrozen) do z:setUseless(u) end;_pzWindowFrozen=nil end')
            r['objectCountAfter']=float(p.lua('return _pzWindowSquare:getObjects():size()')['results'][0])
            assert r['objectCountAfter']==r['objectCountBefore']
            p.lua('_pzWindowSquare=nil')
        except Exception as e:errors.append(str(e))
        r['after']=p.state();r['cleanupErrors']=errors
        if errors:r['passed']=False
        (out/'result.json').write_text(json.dumps(r,indent=2))
    print(json.dumps({'folder':str(out.resolve()),'passed':r['passed'],'error':r.get('error'),'cleanupErrors':errors}))
    raise SystemExit(0 if r['passed'] else 1)


if __name__=='__main__':run()
