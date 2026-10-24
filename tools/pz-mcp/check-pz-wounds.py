"""PZ wounds are cleared while Steve drives (HealthLink.clearWounds).

Injects a spread of PZ wounds into the hidden PZ character (bleeding, bite, scratch, cut, bandage, fracture + splint, infection)
and asserts they are gone within a few seconds with Steve's Minecraft health unchanged. Nothing but wound flags is touched; the
wounds are the fixture and the desired end state is "no wounds". The world is run briefly with every zombie frozen only if the
paused game does not clear them by itself.
"""
import json
import sys
import time
import traceback
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent

SUMMARY = (
    'local b=getPlayer():getBodyDamage();local n=BodyPartType.ToIndex(BodyPartType.MAX);local o={};'
    'for i=0,n-1 do local bp=b:getBodyPart(BodyPartType.FromIndex(i));'
    'local f={};'
    'if bp:bleeding() then f[#f+1]="bleeding" end;if bp:bitten() then f[#f+1]="bitten" end;if bp:scratched() then f[#f+1]="scratched" end;'
    'if bp:deepWounded() then f[#f+1]="deep" end;if bp:isCut() then f[#f+1]="cut" end;if bp:bandaged() then f[#f+1]="bandaged" end;'
    'if bp:isSplint() then f[#f+1]="splint" end;if bp:getFractureTime()>0 then f[#f+1]="fracture" end;if bp:getBurnTime()>0 then f[#f+1]="burn" end;'
    'if bp:IsInfected() then f[#f+1]="infected" end;if bp:getWoundInfectionLevel()>0 then f[#f+1]="woundInfection" end;'
    'if #f>0 then o[#o+1]=tostring(i)..":"..table.concat(f,"+") end end;'
    'return tostring(#o).."|"..table.concat(o,",").."|body-infected="..tostring(b:isInfected())'
)

INJECT = (
    'local b=getPlayer():getBodyDamage();'
    'local h=b:getBodyPart(BodyPartType.Hand_L);h:setBleeding(true);h:setBleedingTime(40);'
    'local a=b:getBodyPart(BodyPartType.UpperArm_R);a:SetBitten(true);a:setBiteTime(40);'
    'local l=b:getBodyPart(BodyPartType.LowerLeg_L);l:setScratched(true,true);l:setFractureTime(30);l:setSplint(true,1);'
    'local t=b:getBodyPart(BodyPartType.Torso_Upper);t:setCut(true);t:setBandaged(true,10);'
    'local f=b:getBodyPart(BodyPartType.Foot_R);f:setBurnTime(20);'
    'b:setInfected(true);'
)


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def run():
    out = HERE / ('pz-wounds-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    assert baseline['paused'] and not baseline['input']['live']
    result = {'before': baseline, 'passed': False, 'samples': []}
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    try:
        result['woundsAtStart'] = lua(SUMMARY)
        steve_hp = baseline['health']['steveHealth']
        cleared0 = baseline['health'].get('woundsCleared', 0)
        # inject and read back in ONE call: the per-frame link may clear the wounds before a second call runs
        injected = lua(INJECT + SUMMARY)
        result['injected'] = injected
        assert not injected.startswith('0|'), 'the fixture wounds were not applied: ' + injected
        # the paused world: does the per-frame link clear them without running the game?
        end = time.time() + 6
        state = None
        while time.time() < end:
            time.sleep(.5)
            now = lua(SUMMARY)
            result['samples'].append({'t': round(time.time() - (end - 6), 2), 'wounds': now})
            if now.startswith('0|') and now.endswith('false'):
                state = now
                break
        if state is None:
            # PZ's per-frame hook did not run while paused: run the world for a moment, zombies frozen, and look again
            lua('getCore():setOptionPauseOnFocusloss(false);_pzWoundHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzWoundHeld,{z,z:isUseless()});z:setUseless(true) end;UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
            result['ranUnpaused'] = True
            end = time.time() + 8
            while time.time() < end:
                time.sleep(.5)
                now = lua(SUMMARY)
                result['samples'].append({'t': round(time.time(), 2), 'wounds': now, 'unpaused': True})
                if now.startswith('0|') and now.endswith('false'):
                    state = now
                    break
        assert state is not None, 'wounds still present: ' + str(result['samples'][-1])
        after = p.state()
        result['stats'] = after['health']
        assert after['health']['woundsCleared'] > cleared0, 'woundsCleared did not move'
        assert abs(after['health']['steveHealth'] - steve_hp) < 0.6, f'Steve health changed {steve_hp} -> {after["health"]["steveHealth"]}'
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e)
        result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('pause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzWoundHeld then for _,v in ipairs(_pzWoundHeld) do v[1]:setUseless(v[2]) end;_pzWoundHeld=nil end'))
        clean('wounds', lambda: lua(SUMMARY).startswith('0|') or errors.append('wounds remain after the test'))
        result['after'] = p.state()
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
