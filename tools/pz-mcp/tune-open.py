"""Open a tuning session with a temporary gun: python tune-open.py Base.Pistol2
Freezes every zombie on every tick (until tune-close.py), selects the borrowed empty slot 8, gives the gun (m9-session start) and leaves the
world paused. Pictures: tune_shot.py NAME key=value ... ; end with tune-close.py (which restores slot, tuning file and the zombie flags)."""
import json, runpy, sys, time
from pathlib import Path
import pzcore as p
HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))
def lua(t):
    v = p.lua(t)['results']; return v[0] if v else None
gun = sys.argv[1] if len(sys.argv) > 1 else 'Base.Pistol2'
base = p.state(); g0 = base['minecraftGui']
assert base['paused'] and g0['inventory'][8].startswith('0 ')
(HERE / 'tune-session.json').write_text(json.dumps({'slot': g0['selectedSlot'], 'camera': g0['cameraMode'], 'look': [base['steve']['yaw'], base['steve']['pitch']],
    'option': lua('return tostring(getCore():getOptionPauseOnFocusloss())'), 'tuning': (Path.home() / '.pzcraft/tuning.json').read_text() if (Path.home() / '.pzcraft/tuning.json').exists() else '{}'}))
lua('_pzTnHeld={};_pzTnSeen={};function _pzTnFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzTnSeen[z] then _pzTnSeen[z]=true;table.insert(_pzTnHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzTnFreeze();Events.OnTick.Add(_pzTnFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
time.sleep(1.0)
try:
    for attempt in range(5):      # the hotbar selection sometimes lags right after another session closed
        try: p._select_slot(8, timeout=8); break
        except Exception:
            if attempt == 4: raise
            time.sleep(1.5)
    p.cmd('camera 0'); p.cmd('look 20 0'); time.sleep(.5)
    lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    m9['start'](gun)
    lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
except Exception:
    # never leave the world running or the fixture behind: the session file stays, so tune-close.py undoes the rest
    lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    runpy.run_path(str(HERE / 'tune-close.py'), run_name='__main__')
    raise
print('tuning session open with', gun)
