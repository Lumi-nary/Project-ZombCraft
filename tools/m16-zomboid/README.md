# Edited Project-ZombCraft M16

The canonical editable project is `../blockbench-models/m16-zomboid/pz-m16-zomboid.bbmodel`.
Keep this edited model and its 23 clips. The procedural `tools/weapons/m16a1.py` model is not its replacement.

`GeckoLib-assets/` contains the matching native Blockbench export: geometry, animations,
texture, display transforms and the M16-only manifest. Export future edits through
Blockbench's GeckoLib codec before updating this directory and its hashes.

`python tools/m16-zomboid/integrate.py` applies only these four M16 files to the bundled
set. `tools/weapons/build_own.py` invokes it after procedural generation, so rebuilding
the other weapons preserves the edited M16. `PROVENANCE.txt` retains the private source history.
