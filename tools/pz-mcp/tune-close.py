"""End a tuning session: python tune-close.py"""
import json, runpy, time
from pathlib import Path
import pzcore as p
HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))
def lua(t):
    v = p.lua(t)['results']; return v[0] if v else None
ses = json.loads((HERE / 'tune-session.json').read_text())
errors = []
def clean(label, fn):
    try: fn()
    except Exception as e: errors.append(label + ': ' + repr(e))
clean('gun', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
clean('look', lambda: p.cmd(f"look {ses['look'][0]} {ses['look'][1]}"))
clean('camera', lambda: p.cmd(f"camera {ses['camera']}"))
for i in range(4):
    try: p._select_slot(ses['slot'], timeout=8); break
    except Exception as e:
        if i == 3: errors.append('slot: ' + repr(e))
        time.sleep(1)
clean('tuning', lambda: (Path.home() / '.pzcraft/tuning.json').write_text(ses['tuning']))
lua('Events.OnTick.Remove(_pzTnFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + ses['option'] + ');if _pzTnHeld then for _,v in ipairs(_pzTnHeld) do v[1]:setUseless(v[2]) end;_pzTnHeld=nil end')
(HERE / 'tune-session.json').unlink()
s = p.state()
print('closed; slot', s['minecraftGui']['selectedSlot'], 'paused', s['paused'], 'hp', s['health']['steveHealth'], 'errors', errors)
