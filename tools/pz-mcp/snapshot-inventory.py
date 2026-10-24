"""Exact inventory snapshot for before/after comparisons: all 36 Minecraft slots and PZ's top-level items by type and id."""
import json, sys, time
import pzcore as p

def snapshot():
    s = p.state(); g = s['minecraftGui']
    pz = p.lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); '
               'o[#o+1]=m:getFullType()..":"..tostring(m:getID())..":"..tostring(m:getCondition())..":"..tostring(m:getCurrentAmmoCount()) end; return table.concat(o,",")')['results'][0].split(',')
    types = {}
    for e in pz:
        t = e.split(':')[0]; types[t] = types.get(t, 0) + 1
    return {'time': time.strftime('%H:%M:%S'), 'paused': s['paused'], 'slot': g['selectedSlot'], 'camera': g['cameraMode'], 'mc': g['inventory'],
            'pzItems': sorted(pz), 'pzCounts': types, 'mirror': {k: s['pzMirror'][k] for k in ('status', 'pending', 'failures')}}

if __name__ == '__main__':
    out = snapshot()
    if len(sys.argv) > 1:
        open(sys.argv[1], 'w').write(json.dumps(out, indent=1))
    print(json.dumps({'mc': [(i, x) for i, x in enumerate(out['mc']) if not x.startswith('0 ')], 'pzCounts': out['pzCounts'], 'mirror': out['mirror']}, indent=0)[:1500])
