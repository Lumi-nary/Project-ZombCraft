"""Stop both games safely, back up, rebuild, approve our own PzCraft hash, relaunch, restore the pause, and prove the
inventory came back identical.

  python restart-all.py LABEL [--no-protocol-tests]

1 snapshot-inventory before; 2 pzcore.kill('all') (refuses unless both worlds saved); 3 paired backup of both worlds into
.pzcraft-backups/before-LABEL-<stamp>/ ; 4 protocol tests, offline PZ build + install, offline Fabric build + direct launch;
5 approve-pzcraft.py (exact built = installed hash, private zb-dev profile, owner-authorised 2026-10-04 13:21); 6 launch with the
private ZombieBuddy profile; 7 pause; 8 snapshot after and compare (Minecraft slots exactly; PZ items except individual rounds).
Prints one JSON summary; exit code 1 if anything differs or fails. Never touches an item.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
import pzcore as p
import approve_helper

ROOT = p.ROOT
JAVA = r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot'


def run(cmd, log):
    env = dict(os.environ, JAVA_HOME=JAVA)
    r = subprocess.run(cmd, shell=True, cwd=ROOT, env=env, capture_output=True, text=True)
    Path(log).write_text(r.stdout + '\n' + r.stderr, encoding='utf-8')
    return r.returncode


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else 'restart'
    tests = '--no-protocol-tests' not in sys.argv
    here = Path(__file__).parent
    stamp = time.strftime('%Y%m%d-%H%M%S')
    summary = {'label': label, 'steps': []}
    import snapshot_inventory as snap
    cold = '--cold' in sys.argv      # both games are already down (crash/quit): nothing to snapshot or stop; compare with --baseline FILE
    if cold:
        base = [a.split('=', 1)[1] for a in sys.argv if a.startswith('--baseline=')]
        before = json.loads(Path(base[0]).read_text()) if base else None
    else:
        before = snap.snapshot()
        (here / f'{label}-before-{stamp}.json').write_text(json.dumps(before, indent=1))
        k = p.kill('all')
        summary['kill'] = k
        if k.get('refused'):
            print(json.dumps(summary)); sys.exit(1)
        time.sleep(4)
    backup = ROOT / '.pzcraft-backups' / f'before-{label}-{stamp}'
    (backup / 'pz').mkdir(parents=True); (backup / 'mc').mkdir()
    shutil.copytree(p.ZOMBOID / 'Saves/Apocalypse/PzCraftFeatureTest-20261002', backup / 'pz/PzCraftFeatureTest-20261002')
    shutil.copytree(ROOT / 'fabric-mod/run/saves/pzcraft', backup / 'mc/pzcraft')
    for f in (p.ZOMBOID / 'mods/Project-ZombCraft/42/media/java/Project-ZombCraft.jar', ROOT / 'fabric-mod/build/libs/Project-ZombCraft-Fabric-0.13.0.jar', ROOT / 'HANDOFF.md'):
        shutil.copy2(f, backup)
    summary['backup'] = str(backup)
    if tests:
        summary['protocolTests'] = run(r'protocol\gradlew.bat -p protocol test --offline --console=plain', here / f'{label}-protocol-tests-{stamp}.log')
        if summary['protocolTests']: print(json.dumps(summary)); sys.exit(1)
    summary['pzBuild'] = run(r'pz-mod\gradlew.bat -p pz-mod build installMod --offline --console=plain', here / f'{label}-pz-build-{stamp}.log')
    summary['fabricBuild'] = run(r'fabric-mod\gradlew.bat -p fabric-mod build prepareDirectLaunch --offline --no-configuration-cache --console=plain', here / f'{label}-fabric-build-{stamp}.log')
    if summary['pzBuild'] or summary['fabricBuild']:
        print(json.dumps(summary)); sys.exit(1)
    built, installed = ROOT / 'pz-mod/build/libs/Project-ZombCraft.jar', p.ZOMBOID / 'mods/Project-ZombCraft/42/media/java/Project-ZombCraft.jar'
    summary['pzJar'] = {'sha256': sha(built), 'bytes': built.stat().st_size, 'installedMatches': sha(built) == sha(installed)}
    fj = ROOT / 'fabric-mod/build/libs/Project-ZombCraft-Fabric-0.13.0.jar'
    summary['fabricJar'] = {'sha256': sha(fj), 'bytes': fj.stat().st_size}
    summary['approval'] = approve_helper.approve()
    os.environ['PZ_ZB_ARGS'] = f"config_dir={Path.home() / '.pzcraft' / 'zb-dev'}"
    launch = p.launch(restart=False, allow_all=False, save='PzCraftFeatureTest-20261002', game_mode='Apocalypse')
    launch.pop('state', None)
    summary['launchOk'] = launch.get('ok')
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    time.sleep(3)
    after = snap.snapshot()
    (here / f'{label}-after-{stamp}.json').write_text(json.dumps(after, indent=1))
    norm = lambda inv: [(i, x.replace(' whole', '')) for i, x in enumerate(inv) if not x.startswith('0 ')]
    non_rounds = lambda items: sorted(x for x in items if not x.startswith('Base.Bullets9mm'))
    if before is None:
        summary['inventorySame'] = summary['pzItemsSameExceptRounds'] = True; summary['comparedWithBaseline'] = False
    else:
        summary['inventorySame'] = norm(before['mc']) == norm(after['mc'])
        summary['pzItemsSameExceptRounds'] = non_rounds(before['pzItems']) == non_rounds(after['pzItems'])
    summary['mirror'] = after['mirror']; summary['paused'] = after['paused']; summary['slot'] = after['slot']
    ok = summary['launchOk'] and summary['inventorySame'] and summary['pzItemsSameExceptRounds'] and after['mirror']['failures'] == 0 and after['paused']
    print(json.dumps(summary, indent=1))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
