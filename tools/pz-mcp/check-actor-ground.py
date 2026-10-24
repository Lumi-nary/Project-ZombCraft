"""One temporary native zombie over reversible ground. Preserve owner items, native actors, floors and input."""
import json
import math
import re
import time
import traceback
import sys
from pathlib import Path
import pzcore as p


def run():
    out=Path(__file__).parent/('actor-ground-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir()
    before=p.state();assert before['paused'] and not before['input']['live'] and not before['overrideActive']
    assert before['pzMirror']['status']=='in step'
    option=str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    result={'passed':False,'before':before,'pauseOnFocusloss':option,'samples':[]};errors=[];original={};tile=None
    def save(): (out/'result.json').write_text(json.dumps(result,indent=2))
    def park():
        return p.lua('_pzPitFrozen=_pzPitFrozen or {};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);'
                     'if not _pzPitFrozen[z] then _pzPitFrozen[z]={z:isUseless()} end;'
                     'z:setUseless(true);end')
    def proxy():
        park();s=p.state();rows=[e for e in s['minecraftGui']['server']['entities'] if e.get('name','').startswith('PzCraftProxy:'+str(result['id'])+':')]
        return rows[0] if rows else None
    def sample(label):
        s=p.state();e=proxy();result['samples'].append({'phase':label,'proxy':e,'terrain':s.get('actorTerrain')});save();return e
    def restore_ground():
        for (x,y,z),state in sorted(original.items(),key=lambda row:row[0][1]):p.cmd(f'mc setblock {x} {y} {z} {state}')
    save()
    try:
        park();p.lua('getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        # Only an otherwise empty native grass tile, with empty Minecraft space above it, can be borrowed.
        candidates=p.lua('local p=getPlayer();local r={};for dx=-5,5 do for dy=-5,5 do if dx*dx+dy*dy>=9 and dx*dx+dy*dy<=25 then '
                         'local s=getCell():getGridSquare(math.floor(p:getX())+dx,math.floor(p:getY())+dy,0);'
                         'if s and s:getFloor() and s:getObjects():size()==1 and s:getWorldObjects():size()==0 and s:getStaticMovingObjects():size()==0 '
                         'and not s:isSolid() and not s:isSolidTrans() and string.find(s:getFloor():getSprite():getName(),"blends_natural") then '
                         'r[#r+1]=s:getX()..","..s:getY()..","..s:getFloor():getSprite():getName() end end end end;return table.concat(r,";")')['results'][0]
        for c in candidates.split(';'):
            if not c:continue
            x,z,name=c.split(',');x=int(x);z=int(z)
            p.cmd('mc pzcraft_probe_clear')
            for y in range(-3,3):p.cmd(f'mc pzcraft_probe pit{y} {x} {y} {z}')
            states=p.wait_for(lambda: p.state()['minecraftGui']['server']['probes'] if len(p.state()['minecraftGui']['server']['probes'])==6 else None,4,.1,'ground probes')
            if any('minecraft:air' not in states['pit'+str(y)] for y in (0,1,2)):continue
            parsed={}
            for y in (-1,-2,-3):
                m=re.fullmatch(r'Block\{(minecraft:(?:grass_block|dirt|stone|gravel))\}(\[.*\])?',states['pit'+str(y)])
                if not m:break
                parsed[(x,y,z)]=m[1]+(m[2] or '')
            if len(parsed)!=3:continue
            tile=(x,z);original=parsed;result['tile']=[x,z];result['floorSprite']=name;result['originalBlocks']={str(k):v for k,v in original.items()};break
        assert tile is not None,'no safe unbuilt grass tile'
        x,z=tile
        n=p.lua('local known={};local a=getCell():getZombieList();for i=0,a:size()-1 do known[a:get(i)]=true end;'
                f'addZombiesInOutfit({x},{z},0,1,nil,50);a=getCell():getZombieList();local n=0;'
                'for i=0,a:size()-1 do local a=a:get(i);if not known[a] then _pzPitTarget=a;a:setUseless(true);a:setHealth(100);'
                f'a:setForceX({x+.5});a:setForceY({z+.5});a:getModData()._PzCraftPitFixture=true;n=n+1 end end;assert(n==1);return _pzPitTarget:getID()')['results'][0]
        result['id']=int(float(n));save()
        e=p.wait_for(lambda:proxy(),8,.15,'temporary native proxy');assert abs(e['xyz'][1])<.04
        p.cmd('look '+str(math.degrees(math.atan2(-(x+.5-before['steve']['x']),z+.5-before['steve']['z'])))+' 28')
        p.send_input([],100,wait=True);p.cmd('inputrelease');time.sleep(.4)
        sample('intact support');(out/'intact.png').write_bytes(p.screenshot(.75))
        for _,y,_ in original:p.cmd(f'mc setblock {x} {y} {z} minecraft:air')
        t=time.monotonic()
        while time.monotonic()-t<7:
            e=sample('falling')
            if e and abs(e['xyz'][1]+3)<.04:break
            time.sleep(.2)
        assert e and abs(e['xyz'][1]+3)<.04,'native proxy did not land at pit bottom'
        result['landingSeconds']=time.monotonic()-t
        y=float(p.lua('return _pzPitTarget:getModData().PzCraftTerrainY')['results'][0]);assert abs(y+3)<.04
        assert p.state()['actorTerrain']['rendered']>0
        sample('landed');(out/'landed.png').write_bytes(p.screenshot(.75))
        if '--reconnect' in sys.argv:
            # PZ stays alive, so exact native fixture references and parked owner-actor flags remain available.
            result['restartBefore']=p.state();save()
            stop=p.kill('minecraft');assert not stop.get('refused'),stop
            p.wait_for(lambda:not p.processes()['minecraft'] or None,15,.3,'Minecraft exit')
            result['restart']=p.start_minecraft()['steps']
            p.wait_for(lambda: (e:=proxy()) is not None and abs(e['xyz'][1]+3)<.04,7,.15,'underground reconnect')
            sample('reconnect retains underground height');save()
        # Repeated native horizontal intent cannot snap the body through the solid side of the pit.
        for _ in range(15):p.lua('_pzPitTarget:setForceX(_pzPitTarget:getX()+.12)');time.sleep(.12)
        e=sample('wall blocks escape');assert e['xyz'][0]<=x+.71 and abs(e['xyz'][1]+3)<.04
        assert p.state()['actorTerrain']['corrected']>before['actorTerrain']['corrected']
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)');a=proxy();time.sleep(1.1);b=proxy();assert a['xyz']==b['xyz'];sample('paused position unchanged')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)');restore_ground()
        def lifted():
            e=proxy();return e if e and abs(e['xyz'][1])<.04 else None
        p.wait_for(lifted,7,.15,'refilled ground lifts native actor')
        sample('refilled support');(out/'refilled.png').write_bytes(p.screenshot(.75))
        if '--edge-entry' in sys.argv:
            p.lua(f'_pzPitTarget:setForceX({x-1.5});_pzPitTarget:setForceY({z+.5});_pzPitTarget:setTarget(nil)')
            time.sleep(.5)
            for _,y,_ in original:p.cmd(f'mc setblock {x} {y} {z} minecraft:air')
            time.sleep(.4)
            # Supply bounded native horizontal intent across the edge, keeping the fixture actor unable to attack.
            t=time.monotonic()
            for n in range(1,21):
                p.lua(f'_pzPitTarget:setForceX({x-1.5+n*.1});_pzPitTarget:setForceY({z+.5})')
                e=sample('bounded native step across pit edge');time.sleep(.1)
                assert p.state()['health']['steveHealth']==before['health']['steveHealth'],'owner health changed'
            def landed():
                e=proxy();return e if e and e['xyz'][1]<-2.95 else None
            p.wait_for(landed,4,.15,'edge-entry landing')
            result['edgeEntrySeconds']=time.monotonic()-t
            sample('edge entry landed');(out/'edge-entry-landed.png').write_bytes(p.screenshot(.75))
        result['passed']=True
    except Exception as e:result['error']=str(e);result['traceback']=traceback.format_exc()
    finally:
        for f in [lambda:p.cmd('inputrelease'),
                  lambda:p.lua('_pzPitAI=nil;if _pzPitTarget then _pzPitFrozen[_pzPitTarget]=nil;_pzPitTarget:removeFromWorld();_pzPitTarget:removeFromSquare();_pzPitTarget=nil end'),
                  lambda:p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'),
                  restore_ground,
                  lambda:p.cmd('mc pzcraft_probe_clear'),
                  lambda:p.cmd('camera '+str(before['minecraftGui']['cameraMode'])),
                  lambda:p.cmd('look '+str(before['steve']['yaw'])+' '+str(before['steve']['pitch'])),
                  lambda:p.cmd('mc tp @s '+str(before['steve']['x'])+' '+str(before['steve']['y'])+' '+str(before['steve']['z'])+' '+str(before['steve']['yaw'])+' '+str(before['steve']['pitch'])) if any(abs(p.state()['steve'][k]-before['steve'][k])>.00001 for k in ('x','y','z')) else None,
                  lambda:p.send_input([],100,wait=True),
                  lambda:p.cmd('inputrelease'),
                  lambda:p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+')'),
                  lambda:p.lua('if _pzPitFrozen then for z,u in pairs(_pzPitFrozen) do z:setUseless(u[1]) end;_pzPitFrozen=nil end')]:
            try:f()
            except Exception as e:errors.append(str(e))
        if tile:
            try:
                x,z=tile;name=p.lua(f'local s=getCell():getGridSquare({x},{z},0);return s:getFloor() and s:getFloor():getSprite():getName() or "none"')['results'][0]
                assert name==result['floorSprite'],'original native floor not restored'
            except Exception as e:errors.append(str(e))
        result['after']=p.state();result['cleanupErrors']=errors
        if errors:result['passed']=False
        if result['after']['minecraftGui']['inventory']!=before['minecraftGui']['inventory'] or result['after']['health']['steveHealth']!=before['health']['steveHealth']:
            result['passed']=False;result['preservationError']='owner inventory or health changed'
        save();print(json.dumps({'folder':str(out.resolve()),'passed':result['passed'],'error':result.get('error'),'cleanupErrors':errors,'preservationError':result.get('preservationError')}))
    raise SystemExit(0 if result['passed'] else 1)


if __name__=='__main__':run()
