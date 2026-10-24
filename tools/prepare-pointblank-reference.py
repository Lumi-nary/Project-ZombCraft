"""Prepare one authorized gun's visual reference outside the project; never install the complete mod.

Only geometry, textures and animation files are copied. Gun mechanics are summarized from selected data fields.
Recipes, workstations, crafting/trading, attachments and sound assets are deliberately outside this allowlist.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def prepare(jar: Path, output: Path):
    project = Path(__file__).resolve().parents[1]
    if output.resolve().is_relative_to(project):
        raise ValueError("Keep licensed reference output outside the project")
    gun = "m9"  # PZ Base.Pistol; the first compatibility spike uses exactly one gun.
    allowed = [
        f"assets/pointblank/geckolib/models/item/{gun}.geo.json",
        f"assets/pointblank/geckolib/animations/item/{gun}.animation.json",
        "assets/pointblank/geckolib/animations/item/common.animation.json",
        "assets/pointblank/geckolib/animations/player/pistol.animation.json",
        f"assets/pointblank/textures/item/{gun}.png",
        f"assets/pointblank/textures/item/{gun}.icon.png",
        f"assets/pointblank/models/item/{gun}_base.json",
        f"assets/pointblank/models/item/{gun}_icon.json",
    ]
    manifest = {"source": str(jar.resolve()), "sourceSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                "gun": gun, "pzType": "Base.Pistol", "pzAmmo": "Base.Bullets9mm", "files": []}
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        manifest["version"] = metadata["version"]
        manifest["declaredDependencies"] = metadata["depends"]
        for name in allowed:
            data = archive.read(name)
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            entry = {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            if name.endswith(".png"):
                assert data[:8] == b"\x89PNG\r\n\x1a\n", name
                entry["dimensions"] = list(struct.unpack(">II", data[16:24]))
            manifest["files"].append(entry)
        geometry = json.loads(archive.read(allowed[0]))["minecraft:geometry"][0]
        animations = json.loads(archive.read(allowed[1]))["animations"]
        gun_data = json.loads(archive.read(f"data/pointblank/items/{gun}.json"))
        manifest["geometry"] = {"bones": len(geometry["bones"]),
                                "cubes": sum(len(b.get("cubes", [])) for b in geometry["bones"]),
                                "textureSize": [geometry["description"]["texture_width"],
                                                geometry["description"]["texture_height"]]}
        manifest["animations"] = {name: {"seconds": a.get("animation_length"), "loop": a.get("loop", False),
                                         "bones": len(a.get("bones", {}))} for name, a in animations.items()}
        fields = ("animationType", "maxAmmoCapacity", "rpm", "fireModes", "inaccuracy", "inaccuracyAiming",
                  "gunRecoilInitialAmplitude", "viewRecoilAmplitude", "phasedReloads", "drawAnimations")
        manifest["mechanicsReference"] = {key: gun_data[key] for key in fields if key in gun_data}
        # PZ remains authoritative for weapon damage, ammo, condition, noise and supply.
        manifest["integration"] = {"installed": False, "minecraft26_3Verified": False,
                                   "soundSource": "Project Zomboid", "gunBuilding": False}
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output.resolve()), "gun": gun, "files": len(allowed),
                      "bones": manifest["geometry"]["bones"], "animations": len(manifest["animations"]),
                      "installed": False}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] /
                        "ref/pointblank-src/pointblank-fabric-26.1.2-2.2.0.jar")
    parser.add_argument("--output", type=Path, default=Path.home() /
                        ".pzcraft/private/pointblank-26.1.2-2.2.0/m9-reference")
    args = parser.parse_args()
    prepare(args.jar, args.output)
