"""0.12 native effects and loading boundaries; run only in the backed-up feature save."""
import argparse,json,math,time
from importlib.machinery import SourceFileLoader
from pathlib import Path
import pzcore as p
OUT=Path(__file__).parent
h=SourceFileLoader('render_checks',str(OUT/'check-render-weather.py')).load_module()

def state(): return p.state()
def mc(command,wait=.3): h.mc(command,wait)
def shot(name): return h.shot('native-012-'+name+'.png',.7)
def wait_for(test,what): return p.wait_for(lambda: state() if test(state()) else None,10,.1,what)

def run(stage):
    result={'passed':False,'stage':stage};flight=False
    try:
        with h.Fixture() as f:
            try:
                mc('pzcraft_test_flight begin fixed 0.075');flight=True
                mc(f'tp @s {f.x+.5} 0.01 {f.y+.5}',1)
                p.cmd('camera 0');p.send_input(look={'yaw':-90,'pitch':0,'relative':False})
                if stage=='loading':
                    # Position just before an eight-tile column boundary; withhold only its acknowledgement.
                    edge=(f.x//8+1)*8;z=f.y+.5;cx=edge//8;cz=f.y//8
                    mc(f'tp @s {edge-1.5} 0.01 {z}',1)
                    before=state()['minecraftGui']['terrain']['holds']
                    mc(f'pzcraft_test_pending {cx} {cz}',.15)
                    p.send_input(hold=['FWD','SPRINT'],ms=3000)
                    samples=[];start=time.monotonic()
                    while time.monotonic()-start<2:
                        s=state();samples.append({'x':s['steve']['x'],'y':s['steve']['y'],'coverage':s['minecraftGui']['terrain']});time.sleep(.05)
                    p.send_input(release=True)
                    result.update(boundary=edge,maxX=max(s['x'] for s in samples),minY=min(s['y'] for s in samples),
                                  holds=state()['minecraftGui']['terrain']['holds']-before,loadingSeen=any(s['coverage']['loading'] for s in samples))
                    assert result['maxX']<=edge-.29,result
                    assert result['holds']>0 and result['loadingSeen'] and result['minY']>=0,result
                    result['blockedShot']=shot('pending')
                    p.cmd('terrain refresh');time.sleep(1)
                    p.send_input(hold=['FWD','SPRINT'],ms=1200);time.sleep(.65);p.send_input(release=True)
                    result['releasedX']=state()['steve']['x'];assert result['releasedX']>edge+.5,result
                    # A real PZ wall must also stop this 29.7 block/s ability speed.
                    wall=f.x+4
                    mc(f'tp @s {wall-2} 0.01 {z}',1)
                    h.lua1(f"_pz12Walls={{}}; for yy={f.y-1},{f.y+1} do local sq=getCell():getGridSquare({wall},yy,0); "
                            "local o=IsoObject.new(sq,'walls_exterior_house_01_0','PzCraftSpeedFixture'); sq:AddTileObject(o); "
                            "sq:RecalcAllWithNeighbours(true); table.insert(_pz12Walls,o) end; return 'walls spawned'")
                    p.cmd('terrain refresh');time.sleep(2)
                    p.send_input(hold=['FWD','SPRINT'],ms=2200);time.sleep(1.8);p.send_input(release=True)
                    result['wallX']=state()['steve']['x'];result['wallBoundary']=wall
                    assert result['wallX']<=wall-.29,result
                    result['wallShot']=shot('fast-wall')
                else:
                    mc('item replace entity @s weapon.mainhand with minecraft:diamond_sword')
                    time.sleep(2) # Let the vanilla equip animation bring the new item onto the screen.
                    s=wait_for(lambda s:s['nativeEntities']['handBatches']>0,'native sword');result['sword']=s['nativeEntities'];result['swordShot']=shot('sword')
                    p.send_input(hold=['ATTACK'],ms=180);time.sleep(.08);result['swingShot']=shot('swing');p.send_input(release=True)
                    for mode in (1,2):
                        p.cmd(f'camera {mode}');time.sleep(.7);s=state()
                        assert s['nativeEntities']['handBatches']==0,s['nativeEntities']
                        assert s['minecraftGui']['videoEntities']==0
                        result[f'camera{mode}Shot']=shot(f'camera-{mode}')
                    p.cmd('camera 0');mc('item replace entity @s weapon.mainhand with minecraft:air')
                    mc(f'tp @s {f.x+.5} 0.01 {f.y+.5}',.5)
                    f.block(f.x+2,1,f.y,'minecraft:stone')
                    mc('gamemode survival');p.send_input(look={'yaw':-90,'pitch':0,'relative':False})
                    p.send_input(hold=['ATTACK'],ms=12000)
                    s=wait_for(lambda s:s['nativeEntities']['decals']>0,'native mining cracks')
                    time.sleep(2);result['mining']=s['nativeEntities'];result['miningShot']=shot('mining');p.send_input(release=True)
                    mc(f'setblock {f.x+2} 1 {f.y} minecraft:air')
                    # The map's rain would immediately extinguish a cow; a temporary roof isolates the flame fixture.
                    for xx in range(f.x+3,f.x+6):
                        for zz in range(f.y,f.y+3):f.block(xx,3,zz,'minecraft:stone')
                    mc('gamemode creative');mc(f'summon minecraft:cow {f.x+4.5} 0.01 {f.y+1.5} {{Tags:["pzcraft_012_flame"],NoAI:1b,Invulnerable:1b,Fire:600s,DeathLootTable:"minecraft:empty"}}',.5)
                    p.send_input(look={'yaw':-75,'pitch':0,'relative':False})
                    s=wait_for(lambda s:s['nativeEntities']['flames']>0,'native burning cow')
                    assert not s['nativeEntities']['failed']
                    result['flame']=s['nativeEntities'];result['flameShot']=shot('flame')
                    assert not s['overlay']['worldVideo'] and not s['minecraftGui']['worldVideo']
                    result['oneVideoLayer']=True
                result['passed']=True
            finally:
                p.send_input(release=True);p.cmd('terrain refresh');p.cmd('camera 0')
                h.lua1("if _pz12Walls then for _,o in ipairs(_pz12Walls) do local sq=o:getSquare(); sq:RemoveTileObject(o); "
                        "sq:RecalcAllWithNeighbours(true) end; _pz12Walls=nil end; return 'fixture walls removed'")
                mc('kill @e[tag=pzcraft_012_flame]')
                if flight:mc('pzcraft_test_flight end',2);flight=False
    except Exception as e:
        result['error']=str(e)
    finally:
        p.send_input(release=True);p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        (OUT/f'native-012-{stage}.json').write_text(json.dumps(result,indent=2))
        print(json.dumps(result,indent=2),flush=True)
    if not result['passed']:raise SystemExit(1)

if __name__=='__main__':
    a=argparse.ArgumentParser(description=__doc__);a.add_argument('stage',choices=['loading','effects']);run(a.parse_args().stage)
