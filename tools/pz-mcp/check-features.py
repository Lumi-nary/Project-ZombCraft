"""Local integration checks on PzCraftFeatureTest-20261002. No external models or image uploads.

Run lifecycle/environment in small stages: python check-features.py lifecycle|environment|doors
Fixtures are removed and both games paused in finally. Results include failed assertions.
"""
import json
import math
import sys
import time
from pathlib import Path
import pzcore as p

ROOT = Path(__file__).parent
result = {}
fixtures = []
fluid_areas = []
start = None
pause_on_focus_loss = None
test_zombies = []

def until(predicate, what, timeout=30):
    def check():
        s = p.state()
        return s if predicate(s) else None
    return p.wait_for(check, timeout, .25, what)

def lua(code):
    r = p.lua(code)
    assert r['ok'], r
    return r['results'][0] if r['results'] else None

def command(line):
    r = p.cmd('mc ' + line)
    assert not r.get('result','').startswith('error'), r
    # The control API queues this for the Minecraft server; yield before the next fixture or observation.
    time.sleep(.06)

def shot(name):
    (ROOT / name).write_bytes(p.screenshot(.7))

def click(x,y):
    assert p.api('POST','/gui',dict(x=x,y=y,button=0,action=1))['ok']
    time.sleep(.2)
    assert p.api('POST','/gui',dict(x=x,y=y,button=0,action=0))['ok']

def use_at(x,y,z):
    s=p.state(); eye=s['steve']; height=s['viewpoint']['eye'][1]
    dx=x-eye['x']; dz=z-eye['z']; dy=y-eye['y']-height
    yaw=math.degrees(math.atan2(-dx,dz))
    pitch=-math.degrees(math.atan2(dy,math.hypot(dx,dz)))
    p.send_input(look=dict(yaw=yaw,pitch=pitch,relative=False),hold=['USE'],ms=180,wait=True)

def respawn():
    dead=until(lambda s:s['minecraftGui']['screen']=='DeathScreen' and s['health']['steveHealth']==0,'Minecraft death screen')
    assert dead['player']['health']>0 and dead['gameState']=='IngameState',dead
    time.sleep(2)
    assert p.state()['minecraftGui']['screen']=='DeathScreen','Minecraft auto-respawned'
    g=p.state()['minecraftGui']; click(.5,(g['height']//4+82)/g['height'])
    return until(lambda s:s['health']['steveHealth']>0 and s['link']['ready'] and not s['minecraftGui']['screen'],'manual respawn completion')

def outdoors():
    code='''local p=getPlayer(); local cell=getCell(); local px,py=math.floor(p:getX()),math.floor(p:getY())
local function clear(x,y)
 for dx=-2,8 do for dy=-2,4 do local sq=cell:getGridSquare(x+dx,y+dy,0)
  if not sq or not sq:isFree(false) or sq:getObjects():size()>1 then return false end end end return true end
for r=0,40 do for dx=-r,r do for dy=-r,r do
 if math.max(math.abs(dx),math.abs(dy))==r and clear(px+dx,py+dy) then return (px+dx+0.5)..","..(py+dy+0.5) end
end end end return "none"'''
    found=lua(code); assert found!='none','No clear test area'
    x,z=map(float,found.split(','))
    command(f'tp @s {x} .02 {z}')
    until(lambda s:abs(s['player']['x']-x)<.1 and abs(s['player']['y']-z)<.1,'PZ follows test teleport')
    return math.floor(x),math.floor(z)

def block(x,y,z,state):
    fixtures.append((x,y,z));command(f'setblock {x} {y} {z} {state}')

def probe(name,x,y,z):
    command(f'pzcraft_probe {name} {x} {y} {z}')
    return until(lambda s:name in s['minecraftGui']['server'].get('probes',{}),'block probe')['minecraftGui']['server']['probes'][name]

def assert_block(name,x,y,z,expected):
    probe(name,x,y,z)
    return until(lambda s:expected in s['minecraftGui']['server']['probes'].get(name,''),f'{name} is {expected}')

def lifecycle():
    x,z=outdoors()
    for tick,hour in [(6000,12),(18000,0),(23000,5)]:
        command(f'time set {tick}')
        until(lambda s:abs(s['clock']['hour']-hour)<.5,f'time {tick} reaches PZ')
    command('time add 2000')
    until(lambda s:6.5<s['clock']['hour']<7.5,'time add crosses dawn')
    result['timeSetAdd']=True
    command('time set 6000')
    until(lambda s:11.5<s['clock']['hour']<12.5,'noon')
    block(x+2,0,z,'red_bed[part=foot,facing=east]')
    block(x+3,0,z,'red_bed[part=head,facing=east]')
    time.sleep(2)
    use_at(x+2.5,.4,z+.5)
    until(lambda s:s['minecraftGui'].get('server',{}).get('spawn')==[x+3,0,z],'daytime bed spawn')
    result['bedSetsSpawn']=True
    shot('features-bed.png')
    command(f'tp @s {x+6.5} .02 {z+.5}')
    until(lambda s:abs(s['player']['x']-(x+6.5))<.1,'walk away from bed')
    command('kill @s'); s=respawn()
    assert math.hypot(s['steve']['x']-(x+3.5),s['steve']['z']-(z+.5))<3,s
    until(lambda s:abs(s['player']['x']-s['steve']['x'])<.1,'puppet at bed respawn')
    result['manualBedRespawn']=True
    shot('features-respawn.png')
    command(f'tp @s {x+.5} .02 {z+.5}')
    until(lambda s:abs(s['player']['x']-(x+.5))<.1,'back at bed')
    command('time set 13000')
    until(lambda s:18.5<s['clock']['hour']<19.5,'night')
    use_at(x+2.5,.4,z+.5)
    until(lambda s:5.5<s['clock']['hour']<7.5,'sleep skips PZ night',40)
    result['bedSkipsNight']=True
    # Destroyed-bed fallback must return to the saved world spawn, not the death site.
    command(f'setworldspawn {x} 0 {z}')
    for bx,by,bz in fixtures:command(f'setblock {bx} {by} {bz} air')
    command(f'tp @s {x+6.5} .02 {z+.5}')
    time.sleep(1)
    command('kill @s');s=respawn()
    assert math.hypot(s['steve']['x']-(x+.5),s['steve']['z']-(z+.5))<.2,s
    result['destroyedBedFallback']=True
    command('time pause');time.sleep(1)
    before=p.state()['clock']['hour'];time.sleep(2)
    assert abs(p.state()['clock']['hour']-before)<.001,'PZ clock advanced while /time pause'
    result['timePause']=True
    command('time resume');command('time rate 100');time.sleep(1)
    before=p.state()['clock']['hour'];time.sleep(2)
    assert p.state()['clock']['hour']-before>.1,'PZ clock did not accelerate'
    result['timeRate']=True
    command('time rate 1');command('time set day')
    until(lambda s:6<s['clock']['hour']<8,'named day marker')
    result['timeMarker']=True

def environment():
    x,z=outdoors();command('time set 18000')
    until(lambda s:s['clock']['hour']<.5,'midnight')
    base=p.state()['minecraftLights']
    light_before=int(p.cmd(f'lightprobe {x+1} {z} 0')['result'])
    block(x+2,0,z,'glowstone')
    until(lambda s:s['minecraftLights']>base,'glowstone registered in PZ')
    p.wait_for(lambda:int(p.cmd(f'lightprobe {x+1} {z} 0')['result'])>light_before,20,.4,'glowstone illuminates PZ floor')
    result['glowstone']=True
    shot('features-glowstone.png')
    command(f'setblock {x+2} 0 {z} air')
    until(lambda s:s['minecraftLights']==base,'glowstone cleanup')
    block(x+2,0,z,'redstone_lamp')
    block(x+3,0,z,'redstone_block')
    until(lambda s:s['minecraftLights']>base,'powered lamp')
    result['poweredLamp']=True
    command(f'setblock {x+3} 0 {z} air')
    until(lambda s:s['minecraftLights']==base,'unpowered lamp')
    result['lampOff']=True
    # A PZ floor alone contains water: no invisible Minecraft support block is placed below it.
    original_vertices=p.state()['nativeBlocks']['vertices']
    fluid_areas.append((x-5,-3,z-10,x+15,3,z+10))
    block(x+5,0,z,'water')
    until(lambda s:s['nativeBlocks']['vertices']>original_vertices and s['nativeBlocks']['active'],'native water')
    time.sleep(3)
    assert not p.state()['nativeBlocks']['failed'],p.state()['nativeBlocks']
    assert_block('water_below',x+5,-1,z,'minecraft:air')
    assert_block('water_neighbor',x+6,0,z,'minecraft:water')
    result['nativeWater']=True
    shot('features-water.png')
    command('time set 6000')
    until(lambda s:11.5<s['clock']['hour']<12.5,'daytime cow test')
    triangles=p.state()['nativeEntities']['triangles']
    command(f'summon cow {x+1.5} .02 {z+2.5} {{Tags:["pzcraft_feature_test"],NoAI:1b}}')
    until(lambda s:s['nativeEntities']['triangles']>triangles and not s['nativeEntities']['failed'],'native cow')
    s=p.state();dx=x+1.5-s['steve']['x'];dz=z+2.5-s['steve']['z']
    p.send_input(look=dict(yaw=math.degrees(math.atan2(-dx,dz)),pitch=10,relative=False),ms=300,wait=True)
    time.sleep(1)
    result['nativeCow']=True
    shot('features-cow.png')

def doors():
    data=lua('''local p=getPlayer();local cell=getCell(); for x=math.floor(p:getX())-12,math.floor(p:getX())+12 do
for y=math.floor(p:getY())-30,math.floor(p:getY())+30 do local sq=cell:getGridSquare(x,y,0)
if sq then for i=0,sq:getSpecialObjects():size()-1 do local d=sq:getSpecialObjects():get(i)
if instanceof(d,"IsoDoor") and not d:isLocked() then _featureDoor=d;return x..","..y..","..tostring(d:getNorth()) end end end end end return "none"''')
    assert data!='none','No nearby PZ door'
    x,z,north=data.split(',');x=int(x);z=int(z);north=north=='true'
    before=lua('return tostring(_featureDoor:IsOpen())')
    tx=x+.5 if north else x;tz=z if north else z+.5
    px=tx if north else tx+1.4;pz=tz+1.4 if north else tz
    command(f'tp @s {px} .02 {pz}')
    until(lambda s:abs(s['player']['x']-px)<.1 and abs(s['player']['y']-pz)<.1,'at PZ door')
    use_at(tx,1.2,tz)
    p.wait_for(lambda:lua('return tostring(_featureDoor:IsOpen())')!=before,10,.3,'right-click door toggle')
    result['rightClickDoor']=True
    use_at(tx,1.2,tz)
    p.wait_for(lambda:lua('return tostring(_featureDoor:IsOpen())')==before,10,.3,'door closes')
    result['doorCloses']=True
    # Power the air voxel at the PZ door with an adjacent vanilla redstone source.
    if before=='true':use_at(tx,1.2,tz);time.sleep(.5)
    block(x+1,0,z,'redstone_block')
    p.wait_for(lambda:lua('return tostring(_featureDoor:IsOpen())')=='true',10,.3,'redstone opens PZ door')
    result['redstonePzDoor']=True
    command(f'setblock {x+1} 0 {z} air')
    p.wait_for(lambda:lua('return tostring(_featureDoor:IsOpen())')=='false',10,.3,'redstone off closes PZ door')
    if before=='true':use_at(tx,1.2,tz)
    shot('features-door.png')

def transport():
    x,z=outdoors()
    ident=int(float(lua(f'local a=addZombiesInOutfit({x+3},{z},0,1,nil,50); _featureRider=a:get(0); _featureRider:setUseless(true); return _featureRider:getID()')))
    test_zombies.append('_featureRider')
    until(lambda s:any(e['proxy'] and f'PzCraftProxy:{ident}:' in e.get('name','') for e in s['minecraftGui']['server'].get('entities',[])),'rider proxy')
    for vehicle in ['oak_boat','minecart']:
        if vehicle=='minecart':
            for i in range(1,9):block(x+i,0,z,'rail[shape=east_west]')
        command(f'summon {vehicle} {x+3.5} .1 {z+.5} {{Tags:["pzcraft_feature_test","pzcraft_vehicle"]}}')
        if vehicle=='minecart':command('data modify entity @e[tag=pzcraft_vehicle,limit=1] Motion set value [0.2d,0.0d,0.0d]')
        result[vehicle+'RiderBefore']=lua('return _featureRider:getID()..",".._featureRider:getHealth()..","..tostring(_featureRider:isDead())..",".._featureRider:getX()..",".._featureRider:getY()..",".._featureRider:getZ()')
        until(lambda s:s['minecraftPassengers']>0,f'{vehicle} auto-boards PZ zombie')
        result[vehicle+'Board']=True
        command(f'tp @e[tag=pzcraft_vehicle,limit=1] {x+6.5} .1 {z+.5}')
        p.wait_for(lambda:abs(float(lua('return _featureRider:getX()'))-(x+6.5))<1,10,.25,'PZ rider follows vehicle')
        result[vehicle+'Pose']=True
        shot('features-'+vehicle+'.png')
        command('kill @e[tag=pzcraft_vehicle]')
        until(lambda s:s['minecraftPassengers']==0,'vehicle destruction releases PZ rider')
        result[vehicle+'Release']=True
        lua(f'_featureRider:setForceX({x+3.5});_featureRider:setForceY({z+.5});_featureRider:setZ(0)')
        time.sleep(4)

def fluids():
    x,z=outdoors()
    fluid_areas.append((x-5,-3,z-10,x+15,4,z+10))
    # A bounded deep pool on the native PZ floor, with vanilla water physics and drowning.
    for bx in range(x+2,x+7):
        for bz in range(z-2,z+3):
            for y in range(3):block(bx,y,bz,'stone' if bx in (x+2,x+6) or bz in (z-2,z+2) else 'water')
    assert_block('pool_top',x+4,2,z,'minecraft:water')
    command(f'tp @s {x+4.5} .02 {z+.5}')
    until(lambda s:s['minecraftGui']['server']['inWater'] and abs(s['steve']['x']-(x+4.5))<.4,'Steve submerged in native pool')
    p.send_input(hold=['SNEAK'],ms=28000,wait=False)
    result['submergedState']=p.state()
    shot('features-submerged.png')
    before=p.state()['health']['steveHealth']
    until(lambda s:s['minecraftGui']['server']['air']<280,'air decreases underwater')
    result['waterPhysics']=True
    until(lambda s:s['health']['steveHealth']<before,'real drowning damage',25)
    result['drowning']=True
    p.api('POST','/input',dict(release=True))
    command(f'tp @s {x+.5} .02 {z+.5}');p.cmd('heal');time.sleep(1)
    for bx,by,bz in fixtures:command(f'setblock {bx} {by} {bz} air')
    for box in fluid_areas:
        command('fill '+' '.join(map(str,box))+' air replace water')
    block(x+4,0,z,'lava')
    assert_block('lava',x+4,0,z,'minecraft:lava')
    command(f'tp @s {x+4.5} .02 {z+.5}')
    before=p.state()['health']['steveHealth']
    until(lambda s:s['health']['steveHealth']<before and s['minecraftGui']['server']['onFire'],'lava burns Steve')
    result['lavaDamage']=True
    command(f'tp @s {x+.5} .02 {z+.5}');command('effect give @s fire_resistance 15');p.cmd('heal')
    block(x+3,0,z,'water')
    assert_block('lava',x+4,0,z,'minecraft:obsidian')
    result['waterLavaReaction']=True

def redstone():
    x,z=outdoors()
    block(x,0,z,'lever[face=floor,facing=east]')
    for bx in [x+1,x+2]:block(bx,0,z,'redstone_wire')
    block(x+3,0,z,'repeater[facing=west,delay=2]')
    block(x+4,0,z,'redstone_lamp')
    command(f'tp @s {x+.5} .02 {z+2.5}')
    time.sleep(1);use_at(x+.5,.2,z+.5)
    assert_block('lever',x,0,z,'powered=true')
    assert_block('repeater',x+3,0,z,'powered=true')
    assert_block('lamp',x+4,0,z,'lit=true')
    result['leverWireRepeaterLamp']=True
    use_at(x+.5,.2,z+.5)
    assert_block('lamp',x+4,0,z,'lit=false')
    result['circuitOff']=True
    block(x+2,0,z+3,'piston[facing=east]')
    block(x+3,0,z+3,'stone')
    block(x+1,0,z+3,'redstone_block')
    fixtures.append((x+4,0,z+3))
    assert_block('piston',x+2,0,z+3,'extended=true')
    assert_block('pushed',x+4,0,z+3,'minecraft:stone')
    result['pistonMovesBlock']=True
    block(x+6,0,z+1,'iron_door[half=lower,facing=east]')
    block(x+6,1,z+1,'iron_door[half=upper,facing=east]')
    block(x+5,0,z+1,'redstone_block')
    assert_block('mc_door',x+6,0,z+1,'open=true')
    result['minecraftDoor']=True
    shot('features-redstone.png')

def distant():
    x,z=outdoors()
    command('time set 6000')
    block(x+2,0,z,'red_bed[part=foot,facing=east]')
    block(x+3,0,z,'red_bed[part=head,facing=east]')
    time.sleep(1);use_at(x+2.5,.4,z+.5)
    until(lambda s:s['minecraftGui']['server'].get('spawn')==[x+3,0,z],'distant bed spawn set')
    # The PZ helper loads the distant destination before releasing Steve's physics.
    p.cmd('tp 90 90')
    until(lambda s:s['link']['ready'] and math.hypot(s['steve']['x']-x,s['steve']['z']-z)>80,'away from bed')
    command('kill @s');s=respawn()
    assert math.hypot(s['steve']['x']-(x+3.5),s['steve']['z']-(z+.5))<3,s
    result['distantBedRespawn']=True
    shot('features-distant-respawn.png')

def animals():
    x,z=outdoors()
    ident=int(float(lua(f'''local def=AnimalDefinitions.getDef("cow");local breed=def:getBreedByName("angus");
_featureAnimal=addAnimal(getCell(),{x+3},{z},0,"cow",breed);_featureAnimal:addToWorld();return _featureAnimal:getID()''')))
    test_zombies.append('_featureAnimal')
    until(lambda s:any(f'PzCraftProxy:{ident}:animal'==e.get('name','') for e in s['minecraftGui']['server'].get('entities',[])),'PZ cow proxy')
    command(f'summon oak_boat {x+3.5} .1 {z+.5} {{Tags:["pzcraft_feature_test","pzcraft_vehicle"]}}')
    until(lambda s:s['minecraftPassengers']>0,'PZ animal boards boat')
    result['animalBoards']=True
    command(f'tp @e[tag=pzcraft_vehicle,limit=1] {x+6.5} .1 {z+.5}')
    p.wait_for(lambda:abs(float(lua('return _featureAnimal:getX()'))-(x+6.5))<1,10,.25,'PZ cow follows boat')
    result['animalFollows']=True
    shot('features-animal.png')
    command('kill @e[tag=pzcraft_vehicle]')
    until(lambda s:s['minecraftPassengers']==0,'animal released')
    result['animalReleased']=True

def upper():
    data=lua('''local p=getPlayer();local cell=getCell();for x=math.floor(p:getX())-35,math.floor(p:getX())+35 do
for y=math.floor(p:getY())-35,math.floor(p:getY())+35 do local sq=cell:getGridSquare(x,y,1)
if sq and sq:hasFloor() and not sq:isSolid() and not sq:isSolidTrans() and sq:getObjects():size()<=1 then
return x..","..y end end end return "none"''')
    assert data!='none','No loaded clear upper-storey PZ floor'
    x,z=map(int,data.split(','))
    command(f'setworldspawn {x} 2 {z}')
    # Any old bed has been removed by earlier test cleanup, forcing the world-spawn fallback.
    command('kill @s');s=respawn()
    assert math.hypot(s['steve']['x']-(x+.5),s['steve']['z']-(z+.5))<.2,s
    until(lambda s:2.43<s['steve']['y']<2.5 and s['steve']['onGround'],'fractional upper PZ floor supports respawn')
    result['upperWorldRespawn']=True
    shot('features-upper-respawn.png')

def entities():
    x,z=outdoors();p.cmd('heal');command('time set 18000')
    command(f'tp @s {x+.5} .02 {z+.5}')
    before=p.state()['health']['steveHealth']
    command(f'summon husk {x+4.5} .02 {z+.5} {{Tags:["pzcraft_feature_test","pzcraft_hostile"]}}')
    until(lambda s:any('pzcraft_hostile' in e.get('tags',[]) and e['xyz'][0]<x+3.5 for e in s['minecraftGui']['server'].get('entities',[])),'Minecraft hostile walks on PZ floor')
    result['hostileAi']=True
    until(lambda s:s['health']['steveHealth']<before,'Minecraft hostile damages Steve')
    result['hostileCombat']=True
    assert not p.state()['nativeEntities']['failed']
    shot('features-hostile.png')
    command('kill @e[tag=pzcraft_hostile]');p.cmd('heal')
    # a killed mob stays hittable through its one-second death animation: let it go before the melee check
    until(lambda s:not any('pzcraft_hostile' in e.get('tags',[]) for e in s['minecraftGui']['server'].get('entities',[])),'husk removed')
    time.sleep(1.0)
    command(f'summon cow {x+2.5} .02 {z+.5} {{Tags:["pzcraft_feature_test","pzcraft_cow"],NoAI:1b}}')
    until(lambda s:any('pzcraft_cow' in e.get('tags',[]) for e in s['minecraftGui']['server'].get('entities',[])),'cow for melee')
    use_at(x+2.5,1,z+.5) # aim with the actual Viewpoint eye
    p.send_input(hold=['ATTACK'],ms=160,wait=True)
    until(lambda s:any('pzcraft_cow' in e.get('tags',[]) and e['health']<10 for e in s['minecraftGui']['server'].get('entities',[])),'native cow takes Minecraft melee damage')
    result['entityMelee']=True

stage=sys.argv[1] if len(sys.argv)>1 else 'lifecycle'
try:
    start=p.state();pause_on_focus_loss=lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    lua('getCore():setOptionPauseOnFocusloss(false)');p.desktop('focus');lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    until(lambda s:s['viewpoint']['captured'] and s['link']['ready'],'game ready')
    lua('_featureHeld={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_featureHeld,{z,z:isUseless()});z:setUseless(true) end')
    command('gamerule keep_inventory true')
    {'lifecycle':lifecycle,'environment':environment,'doors':doors,'transport':transport,'fluids':fluids,'redstone':redstone,'entities':entities,'distant':distant,'animals':animals,'upper':upper}[stage]()
    result['pass']=True
except Exception as exc:
    result['pass']=False;result['error']=str(exc)
    try:result['failureState']=p.state()
    except Exception:pass
    try:shot('features-'+stage+'-failure.png')
    except Exception:pass
finally:
    try:
        p.api('POST','/input',dict(release=True))
        for zombie in test_zombies:lua(f'if {zombie} then {zombie}:removeFromWorld();{zombie}:removeFromSquare();{zombie}=nil end')
        command('time rate 1');command('time resume')
        for x,y,z in fixtures:command(f'setblock {x} {y} {z} air')
        for x0,y0,z0,x1,y1,z1 in fluid_areas:
            for fluid in ['water','lava']:command(f'fill {x0} {y0} {z0} {x1} {y1} {z1} air replace {fluid}')
        command('pzcraft_probe_clear')
        command('kill @e[tag=pzcraft_feature_test]')
        command('effect give @s minecraft:instant_health 1 5')
        # Commands are queued on Minecraft's server; drain them before pausing the shared game.
        until(lambda s:not s['minecraftGui']['server'].get('probes',{}) and not any('pzcraft_feature_test' in e.get('tags',[]) for e in s['minecraftGui']['server'].get('entities',[])), 'fixture cleanup')
        time.sleep(1)
        lua('if _featureHeld then for _,v in ipairs(_featureHeld) do v[1]:setUseless(v[2]) end end;_featureHeld=nil;UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        result['finalState']=p.state()
        if pause_on_focus_loss is not None:lua('getCore():setOptionPauseOnFocusloss('+pause_on_focus_loss+')')
    except Exception as exc:result['cleanupError']=str(exc)
    (ROOT/('features-'+stage+'-results.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2),flush=True)
if not result['pass']:raise SystemExit(1)
