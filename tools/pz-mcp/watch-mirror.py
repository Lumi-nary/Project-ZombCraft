"""Diagnostic: logs every change to Steve's slots (main, worn) and to the PZ pockets, with a timestamp, until stopped.

  python watch-mirror.py LOGFILE [seconds]

Read-only (control API state and one Lua read); for finding out when and why the mirror moved or removed an item.
"""
import sys
import time
from pathlib import Path
import pzcore as p

POCKETS = r"""
local out = {}
local items = getPlayer():getInventory():getItems()
for i = 0, items:size()-1 do
  local it = items:get(i)
  local line = it:getFullType() .. '#' .. string.format('%.0f', it:getID())
  local md = it:hasModData() and it:getModData().pzcraftMc or nil
  if md then line = line .. 'x' .. tostring(md.n) end
  out[#out+1] = line
end
table.sort(out)
return table.concat(out, ';')
"""


def main():
    log = Path(sys.argv[1])
    limit = float(sys.argv[2]) if len(sys.argv) > 2 else 600
    t0 = time.time()
    last_mc, last_pz, last_stats = None, None, None
    with open(log, 'w', encoding='utf-8') as f:
        def out(msg):
            f.write(f'{time.time() - t0:7.2f}  {msg}\n')
            f.flush()
        while time.time() - t0 < limit:
            try:
                s = p.state()
                g = s['minecraftGui']
                mc = g['inventory'] + ['worn:' + w for w in g['worn']]
                if last_mc is not None:
                    for i, (a, b) in enumerate(zip(last_mc, mc)):
                        if a != b:
                            out(f'MC slot {i}: {a!r} -> {b!r}')
                last_mc = mc
                pz = p.lua(POCKETS)['results'][0]
                if pz != last_pz:
                    if last_pz is not None:
                        old = set(last_pz.split(';')) - {''}
                        new = set(pz.split(';')) - {''}
                        out(f'PZ pockets: +{sorted(new - old)} -{sorted(old - new)}')
                    last_pz = pz
                m = (s['pzMirror']['txSent'], s['pzMirror']['txAcked'], g['mirror']['transactions'], g['mirror']['added'], g['mirror']['removed'], g['mirror']['changed'])
                if m != last_stats:
                    out(f'stats pz tx sent/acked={m[0]}/{m[1]}  mc applied={m[2]} added={m[3]} removed={m[4]} changed={m[5]}  mc.last={g["mirror"]["last"]!r}  pz.last={s["pzMirror"]["last"]!r}')
                    last_stats = m
            except Exception as e:
                out(f'(read failed: {e})')
            time.sleep(0.15)


if __name__ == '__main__':
    main()
