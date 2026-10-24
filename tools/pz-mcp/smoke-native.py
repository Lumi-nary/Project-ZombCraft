"""Local-only checks of the real two-game bridge. Run with both rebuilt mods installed."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
results = {}
def sample(name):
    s = pz.state()
    results[name] = s
    (OUT / 'native-smoke-results.json').write_text(json.dumps(results, indent=2))
    print(name, json.dumps({k: s.get(k) for k in ('fps', 'paused', 'player', 'steve', 'health', 'vehicles', 'nativeBlocks')}), flush=True)
    return s
def shot(name):
    (OUT / (name + '.png')).write_bytes(pz.screenshot(.7))
def lua(code):
    r = pz.lua(code)
    if not r.get('ok'): raise RuntimeError(r)
    return r

if __name__ == '__main__':
    print(json.dumps(pz.launch(allow_all=True, save='2026-10-01_22-39-36PzCraftTest')), flush=True)
    lua('getPlayer():setGhostMode(true,true); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    pz.cmd('heal'); time.sleep(1)
    sample('ready')
    pz.send_input(look={'yaw': 0, 'pitch': 35, 'relative': False}, hotbar=3, wait=True)
    pz.send_input(['USE'], 120, wait=True); time.sleep(.5)
    shot('native-final-day')
    sample('placed')
    lua('getGameTime():setTimeOfDay(0)'); time.sleep(2)
    shot('native-final-night'); sample('night')
    lua('getGameTime():setTimeOfDay(12)'); time.sleep(2)
    shot('native-final-noon')
    pz.cmd('native off'); time.sleep(.8); shot('native-final-fallback')
    pz.cmd('native on'); time.sleep(.8)
    pz.cmd('hurt 20'); time.sleep(.7); sample('hurt')
    pz.cmd('hunger 0'); time.sleep(8); sample('regenerated')
    pz.cmd('heal'); time.sleep(.5)
    lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)'); time.sleep(.5)
    sample('paused_before'); time.sleep(2); sample('paused_after')
    print('Basic smoke checks finished. Game paused; inspect images and results locally.', flush=True)
