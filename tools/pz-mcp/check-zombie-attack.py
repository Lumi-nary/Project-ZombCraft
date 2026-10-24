"""Does a PZ zombie next to Steve reach its attack animation, and does the hit reach Minecraft?

Pulls one existing zombie to Steve (the same `pull` check-jump-combat.py uses), or with --walk spawns one 3 tiles away
and lets it walk in, then samples for up to 30 s: the zombie's AI state, whether it is attacking, its alpha for player 0,
and the forwarded-hit counter. It also counts NullPointerException lines in console.txt, the B18 crash signature, and
`boredom tick skipped` lines (BoredomGuard). Nothing is killed, teleported or mined. Results go to
zombie-attack-<time>/result.json.

B31 (zombies stand at Steve and never attack): each sample also has the inputs of PZ's attack gate. A zombie goes from
idle or lunge to its attack only when the variable bAttack (IsoZombie.getShouldAttack) and isFacingTarget are true, and
getShouldAttack needs, for a standing zombie: |target z - zombie z| < 0.2, no wall/fence between their squares
(isSomethingTo), at most 0.72 tiles to the target, the target not on the floor, not in a car, not a ghost.
Sample fields: id|dist|state|attacking|alpha|hasTarget|dz|between|bAttack|facing|crawling|targetState
"""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent / f'zombie-attack-{time.strftime("%Y%m%d-%H%M%S")}'
OUT.mkdir(exist_ok=True)

PROBE = """
local p = getPlayer()
local out = {}
local zl = getCell():getZombieList()
for i = 0, zl:size() - 1 do
  local z = zl:get(i)
  local d = math.sqrt((z:getX() - p:getX())^2 + (z:getY() - p:getY())^2)
  if d < 2.5 then
    local st = "?"; pcall(function() st = tostring(z:getCurrentState()) end)
    local att = "?"; pcall(function() att = tostring(z:isAttacking()) end)
    local alpha = "?"; pcall(function() alpha = tostring(z:getAlpha(0)) end)
    local tgt = "nil"; pcall(function() tgt = tostring(z:getTarget() ~= nil) end)
    local dz = "?"; pcall(function() dz = string.format("%.3f", p:getZ() - z:getZ()) end)
    local between = "?"; pcall(function() between = tostring(z:getCurrentSquare():isSomethingTo(p:getCurrentSquare())) end)
    local bAttack = "?"; pcall(function() bAttack = tostring(z:getVariableBoolean("bAttack")) end)
    local facing = "?"; pcall(function() facing = tostring(z:isFacingTarget()) end)
    local crawl = "?"; pcall(function() crawl = tostring(z:isCrawling()) end)
    local pst = "?"; pcall(function() pst = tostring(p:getCurrentState()) end)
    table.insert(out, string.format("%d|%.2f|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s", z:getID(), d, (st:match("states%.(%w+)") or st),
      att, alpha, tgt, dz, between, bAttack, facing, crawl, (pst:match("states%.(%w+)") or pst)))
  end
end
return table.concat(out, ";")
"""


def lua(code):
    r = pz.lua(code)
    if not r.get('ok'):
        raise RuntimeError(r)
    return r['results'][0] if r.get('results') else ''


def count_in_console(pattern):
    text = pz.logs(source='console', pattern=pattern, n=2000)
    return text.count(pattern.split('|')[0]) if text else 0


def main():
    import sys
    walk = '--walk' in sys.argv
    assert pz.alive(), 'PZ control API is not reachable (is PZ running with PzCraft?)'
    s0 = pz.state()
    assert s0['inGame'] and s0['link']['ready'], f'not in game with the link ready: {s0["gameState"]}'
    assert not s0['paused'], 'the game is paused'
    base_forwards = s0['health']['zombieAttacksForwarded']
    base_npe = count_in_console('NullPointerException')
    base_skip = count_in_console('boredom tick skipped')
    result = {'before': {'forwards': base_forwards, 'npe': base_npe, 'skipped': base_skip,
                         'steveHP': s0['health']['steveHealth'], 'steveSquare': s0['player']['hasSquare']},
              'samples': []}
    print('before', result['before'], flush=True)

    print(pz.cmd('spawn 1 3') if walk else pz.cmd('pull 1.0'), flush=True)
    result['mode'] = 'walk' if walk else 'pull'
    result['steve'] = lua('local p = getPlayer() return string.format("%.3f,%.3f,%.4f ghost=%s floor=%s car=%s", '
                          'p:getX(), p:getY(), p:getZ(), tostring(p:isGhostMode()), tostring(p:isOnFloor()), tostring(p:getVehicle() ~= nil))')
    deadline = time.time() + 30
    forwards = base_forwards
    while time.time() < deadline:
        time.sleep(1.0)
        s = pz.state()
        forwards = s['health']['zombieAttacksForwarded']
        sample = {'t': round(time.time(), 1), 'forwards': forwards, 'steveHP': s['health']['steveHealth'],
                  'zombies': lua(PROBE)}
        result['samples'].append(sample)
        print(json.dumps(sample), flush=True)
        if forwards > base_forwards:
            break

    npe = count_in_console('NullPointerException')
    skip = count_in_console('boredom tick skipped')
    result['after'] = {'forwards': forwards, 'npe': npe, 'skipped': skip,
                       'newNPE': npe - base_npe, 'newSkipped': skip - base_skip,
                       'attackedOnce': forwards > base_forwards}
    (OUT / 'result.json').write_text(json.dumps(result, indent=2))
    print('after', result['after'], flush=True)
    print('written', OUT, flush=True)


if __name__ == '__main__':
    main()
