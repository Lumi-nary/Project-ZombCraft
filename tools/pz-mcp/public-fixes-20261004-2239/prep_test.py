"""Prepare an isolated Minecraft launch for the release jar: own game folder, own java.io.tmpdir (so its shared-memory
link cannot meet the owner's games), the release jar loaded from mods/ instead of the dev class folders."""
import shutil, sys
from pathlib import Path

S = Path(__file__).resolve().parent
DEV = Path(r"C:\Users\Maple\Desktop\Pz x Minecraft")
REL = Path(r"C:\Users\Maple\Desktop\Project-ZombCraft-release")
name = sys.argv[1]                      # run name, e.g. run1
extra = sys.argv[2:]                    # extra JVM args

run = S / name
mods = run / "mods"
tmp = S / (name + "-tmp")
for d in (mods, tmp):
    d.mkdir(parents=True, exist_ok=True)
shutil.copy2(REL / "fabric-mod/build/libs/pzcraft-fabric-0.13.1.jar", mods)
shutil.copy2(DEV / "fabric-mod/run/mods/geckolib-fabric-26.3-5.5.7.jar", mods)

cfg = (DEV / "fabric-mod/.gradle/loom-cache/launch.cfg").read_text(encoding="utf-8")
cfg = "\n".join(l for l in cfg.splitlines() if "fabric.classPathGroups" not in l) + "\n"
(S / (name + "-launch.cfg")).write_text(cfg, encoding="utf-8")

def unq(line):  # java @argfile quoting, as written by prepareDirectLaunch
    return line[1:-1].replace('\\"', '"').replace("\\\\", "\\")
def q(v):
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'

args = [unq(l) for l in (DEV / "fabric-mod/build/pzcraft-direct.args").read_text(encoding="utf-8").splitlines() if l.strip()]
out = []
i = 0
while i < len(args):
    a = args[i]
    if a.startswith("-Dfabric.dli.config="):
        a = "-Dfabric.dli.config=" + str(S / (name + "-launch.cfg"))
        assert " " not in a, "dli.config needs @@0020 escapes for spaces"
    if a == "-classpath":
        cp = [p for p in args[i + 1].split(";") if not p.startswith(str(DEV / "fabric-mod" / "build"))]
        assert len(cp) == len(args[i + 1].split(";")) - 2, "expected to drop exactly the two dev output folders"
        out += [a, ";".join(cp)]
        i += 2
        continue
    out.append(a)
    i += 1
main = out.index("net.fabricmc.devlaunchinjector.Main")
out[main:main] = ["-Djava.io.tmpdir=" + str(tmp)] + list(extra)
(S / (name + ".args")).write_text("\n".join(q(a) for a in out) + "\n", encoding="utf-8")
print("prepared", run, "tmp", tmp, "extra", extra)
print("mods:", [p.name for p in mods.iterdir()])
