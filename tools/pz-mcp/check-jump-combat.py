"""Exercise placed-block jumping, mining and a real PZ zombie's Minecraft attack."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
results = {}
def sample(name):
    s = pz.state(); results[name] = s
    assert s['player']['health'] > 0 and s['health']['minecraftOwnsHealth'], f'{name}: PZ lost Minecraft health ownership'
    assert not s['paused'], f'{name}: focus loss paused the experiment'
    (OUT / 'jump-combat-results.json').write_text(json.dumps(results, indent=2))
    print(name, json.dumps({k:s.get(k) for k in ('player','steve','health','blocks','nativeBlocks')}),flush=True)
    return s
def lua(code):
    r = pz.lua(code)
    if not r.get('ok'): raise RuntimeError(r)
    return r
def teleport(x,z):
    s = pz.state()['player']; pz.cmd(f'tp {x-s["x"]} {z-s["y"]}')
    time.sleep(1)
    pz.wait_for(lambda:pz.state()['link']['ready'],20,.2,'placement')

if __name__ == '__main__':
    focus_pause = lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]
    lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)'); pz.cmd('heal')
    try:
        pz.cmd('block 10755 0 9992 minecraft:oak_planks'); pz.cmd('block 10754 0 9992 minecraft:oak_planks'); time.sleep(.5)
        teleport(10755.5,9990.5)
        pz.send_input(look={'yaw':0,'pitch':0,'relative':False},wait=True)
        pz.send_input(['FWD'],650,wait=True); time.sleep(.2); sample('block_stopped_walk')
        pz.send_input(['FWD','JUMP'],220,wait=True); sample('jumping')
        time.sleep(1); sample('landed')
        assert results['landed']['steve']['onGround'] and abs(results['landed']['steve']['y'] - 1) < .01
        # Mine the same fixture from ground level.
        teleport(10755.5,9990.5)
        pz.send_input(look={'yaw':0,'pitch':20,'relative':False},hotbar=2,wait=True)
        pz.send_input(['ATTACK'],3000,wait=True); time.sleep(.5); sample('mined')
        pz.cmd('block 10755 0 9992 minecraft:air'); time.sleep(.2)
        pz.cmd('block 10754 0 9992 minecraft:air'); time.sleep(.2)
        # Pull an existing zombie to open road. Actual PZ AI chooses when to attack.
        pz.cmd('heal')
        teleport(10755.5,9988.5)
        pz.send_input(look={'yaw':0,'pitch':0,'relative':False},hotbar=1,wait=True)
        before=sample('combat_before')
        lua('getPlayer():Kill(nil)'); time.sleep(.3); sample('pz_kill_rejected')
        pz.cmd('pull 1.0')
        deadline=time.time()+15
        while time.time()<deadline:
            s=pz.state()
            if s['health']['zombieAttacksForwarded'] > before['health']['zombieAttacksForwarded']: break
            time.sleep(.25)
        sample('zombie_attack')
        assert results['zombie_attack']['health']['zombieAttacksForwarded'] > before['health']['zombieAttacksForwarded'], 'PZ zombie did not attack'
        print('injuries',lua('local n=0; local a=getPlayer():getBodyDamage():getBodyParts(); for i=0,a:size()-1 do local p=a:get(i); if p:bitten() or p:scratched() then n=n+1 end end; return n'),flush=True)
        pz.send_input(['ATTACK'],1500,wait=True); time.sleep(.5); sample('sword_hit')
        (OUT/'native-combat.png').write_bytes(pz.screenshot(.7))
    finally:
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        pz.cmd('block 10755 0 9992 minecraft:air'); pz.cmd('heal')
        pz.cmd('block 10754 0 9992 minecraft:air')
        time.sleep(.6)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        lua('getCore():setOptionPauseOnFocusloss(' + str(focus_pause).lower() + ')')
