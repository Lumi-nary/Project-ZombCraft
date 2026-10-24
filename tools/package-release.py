"""Build and stage the two release files players install, without publishing anything.

    python tools/package-release.py 0.16.0 [--license arr|apache] [--out release-staging]

Writes <out>/<version>/:
  Project-ZombCraft-Fabric-<version>+mc26.3.jar    the Minecraft (Fabric) mod for 26.3: fabric-mod built with -Pversion=<version>
  Project-ZombCraft-Fabric-<version>+mc1.20.1.jar  the same mod for Minecraft 1.20.1 (fabric-mod/versions/1.20.1, -Pmc1201)
  Project-ZombCraft-Zomboid-<version>.zip    the PZ mod folder: Project-ZombCraft/LICENSE (+ NOTICE), Project-ZombCraft/common/,
                                             Project-ZombCraft/42/mod.info (modversion set to <version>), Project-ZombCraft/42/media/scripts/...,
                                             Project-ZombCraft/42/media/java/Project-ZombCraft.jar
  Workshop/ProjectZombCraft/                 the same mod as a Steam Workshop item for PZ's in-game uploader: workshop.txt
                                             and preview.png from release/workshop/, Contents/mods/Project-ZombCraft/...; copy it to
                                             <Zomboid user folder>/Workshop/ and upload from PZ's main menu (Workshop)
  SHA256SUMS
The dev builds use the version in fabric-mod/gradle.properties (keep it equal to the release number); the staged copies carry the
number given here. The licence (--license): "arr" (default) = All Rights Reserved (LICENSE-ARR), for releases whose source is
not published; "apache" = Apache-2.0 (LICENSE + NOTICE), once it is. Both jars carry it in META-INF and the fabric.mod.json
licence field follows it. Uses the JDK in JAVA_HOME and the gradle wrappers (sh gradlew on Linux, gradlew.bat on Windows). The staging
folder is git-ignored: what goes into a release, and publishing it, is the owner's call.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def gradle(project, *args):
    # the full path: a hardened shell (NoDefaultCurrentDirectoryInExePath) stops cmd finding gradlew.bat in cwd
    wrapper = ['cmd', '/c', str(ROOT / project / 'gradlew.bat')] if os.name == 'nt' else ['sh', 'gradlew']
    print('>', project, ' '.join(args), flush=True)
    subprocess.run(wrapper + ['--no-daemon', *args], cwd=ROOT / project, check=True)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('version')
    ap.add_argument('--license', choices=('arr', 'apache'), default='arr')
    ap.add_argument('--out', default=str(ROOT / 'release-staging'))
    a = ap.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', a.version):
        sys.exit('version must look like 0.16.0')
    out = Path(a.out) / a.version
    out.mkdir(parents=True, exist_ok=True)

    # Minecraft side
    licence = f'-PreleaseLicense={a.license}'
    legal = [(ROOT / 'LICENSE', 'LICENSE'), (ROOT / 'NOTICE', 'NOTICE')] if a.license == 'apache' else [(ROOT / 'LICENSE-ARR', 'LICENSE')]
    gradle('fabric-mod', 'build', ':mc1.20.1:build', '-Pmc1201', f'-Pversion={a.version}', licence)
    built = ROOT / 'fabric-mod' / 'build' / 'libs' / f'Project-ZombCraft-Fabric-{a.version}.jar'
    fabric = out / f'Project-ZombCraft-Fabric-{a.version}+mc26.3.jar'
    shutil.copyfile(built, fabric)
    built120 = ROOT / 'fabric-mod' / 'versions' / '1.20.1' / 'build' / 'libs' / f'Project-ZombCraft-Fabric-{a.version}+mc1.20.1.jar'
    fabric120 = out / built120.name
    shutil.copyfile(built120, fabric120)

    # PZ side
    gradle('pz-mod', 'build', licence)
    jar = ROOT / 'pz-mod' / 'build' / 'libs' / 'Project-ZombCraft.jar'
    mod = ROOT / 'pz-mod' / 'mod'
    info = (mod / '42' / 'mod.info').read_text(encoding='utf-8')
    info = re.sub(r'(?m)^modversion=.*$', f'modversion={a.version}', info)
    info = info.replace(' (personal build)', '')
    # the PZ mod folder, as (path inside it, source file or text)
    folder = [(name, src) for src, name in legal]
    folder.append(('common/', ''))                               # Build 42 looks for a common folder beside the version one
    folder.append(('42/mod.info', info))
    folder += [('42/' + f.relative_to(mod / '42').as_posix(), f) for f in sorted((mod / '42').rglob('*'))
               if f.is_file() and f.name != 'mod.info']
    folder.append(('42/media/java/Project-ZombCraft.jar', jar))

    zomboid = out / f'Project-ZombCraft-Zomboid-{a.version}.zip'
    with zipfile.ZipFile(zomboid, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, src in folder:
            if isinstance(src, Path): z.write(src, 'Project-ZombCraft/' + name)
            else: z.writestr('Project-ZombCraft/' + name, src)

    # Steam Workshop item (PZ checks: preview.png square 256/512 px under 1 MB; Contents/ holds only mods/; no .zip/.exe/...)
    workshop = out / 'Workshop' / 'ProjectZombCraft'
    if workshop.exists():
        shutil.rmtree(workshop)
    contents = workshop / 'Contents' / 'mods' / 'Project-ZombCraft'
    for name, src in folder:
        target = contents / name
        if name.endswith('/'):
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(src, Path): shutil.copyfile(src, target)
        else: target.write_text(src, encoding='utf-8')
    shutil.copyfile(ROOT / 'release' / 'workshop' / 'preview.png', workshop / 'preview.png')
    text = (ROOT / 'release' / 'workshop' / 'workshop.txt').read_text(encoding='utf-8').replace('@VERSION@', a.version)
    (workshop / 'workshop.txt').write_text(text, encoding='utf-8')

    sums = [f'{sha256(p)}  {p.name}' for p in (fabric, fabric120, zomboid)]
    (out / 'SHA256SUMS').write_text('\n'.join(sums) + '\n')
    for p in (fabric, fabric120, zomboid):
        print(f'{p.stat().st_size:>9} B  {p}')
    print('\n'.join(sums))


if __name__ == '__main__':
    main()
