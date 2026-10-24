"""Apply the owner's edited M16 to Project-ZombCraft's bundled gun set.

Run after exporting edits, or let build_own.py apply these exported assets when
regenerating the gun set. This does not build a separate mod or install anything.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPORT = Path(__file__).resolve().parent / 'GeckoLib-assets'
TARGET = ROOT / 'fabric-mod/src/main/resources/pzcraft/guns'
CREDIT = ('The bundled M16 is the owner-edited PZ-style derivative of Vic Point Blank\'s '
          'M16 model, texture and animations, with Codex assistance.\n'
          'Project Zomboid M16_Rifle.x was used for measurements only; its mesh and texture '
          'are not included. Other bundled guns retain their existing provenance.\n'
          'This derivative is for the owner\'s private Project-ZombCraft build.\n')


def apply_to(target):
    """Replace just the M16 entry and its four files, retaining the existing set."""
    target = Path(target)
    manifest_path = target / 'manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if 'm16a1' not in manifest['kinds']:
        return manifest  # a partial regeneration that excludes the M16
    edited = json.loads((EXPORT / 'manifest.json').read_text(encoding='utf-8'))
    expected = {
        'geckolib/models/item/gun_m16a1.geo.json',
        'geckolib/animations/item/gun_m16a1.animation.json',
        'textures/item/gun_m16a1.png', 'models/item/gun_m16a1_base.json',
    }
    if set(edited['files']) != expected or set(edited['kinds']) != {'m16a1'}:
        raise ValueError('Expected an M16-only export with four files')
    payload = {}
    for rel, digest in edited['files'].items():
        data = (EXPORT / 'assets/pzcraft' / rel).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f'Export hash mismatch: {rel}')
        payload[rel] = data
    animations = json.loads(payload['geckolib/animations/item/gun_m16a1.animation.json'])['animations']
    entry = copy.deepcopy(edited['kinds']['m16a1'])
    clips = {name.removeprefix('animation.model.'): clip['animation_length']
             for name, clip in animations.items()}
    if entry['clips'] != clips or len(clips) != 23:
        raise ValueError('Export and manifest animation clips differ')
    # Keep the edited model's private provenance without labelling it as wholly original.
    entry['source'] = 'owner-edited-pz-m16'
    entry['ownerEdited'] = True
    entry['own'] = False
    entry['privateDerivative'] = True
    manifest['kinds']['m16a1'] = entry
    manifest['files'].update(edited['files'])
    # Keep all existing item/type registrations, including Base.AssaultRifle.
    if manifest['types'].get('Base.AssaultRifle') != {'kind': 'm16a1'}:
        raise ValueError('Unexpected existing M16 mapping')
    for rel, data in payload.items():
        dest = target / 'assets/pzcraft' / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    (Path(__file__).resolve().parent / 'PROVENANCE.txt').write_bytes(CREDIT.encode('utf-8'))
    manifest['source'] = 'project-zombcraft-custom-weapons'
    manifest_path.write_bytes(json.dumps(manifest, indent=1).encode('utf-8'))
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=TARGET)
    args = parser.parse_args()
    result = apply_to(args.out)
    print(f"Integrated PZ-style M16: {len(result['kinds'])} kinds, "
          f"{len(result['files'])} files -> {args.out}")
