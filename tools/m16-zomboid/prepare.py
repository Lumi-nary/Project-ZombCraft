"""Prepare reversible edits to the owner's Vic-derived M16; keep the original private."""
from pathlib import Path
import base64
import copy
import io
import json
import math
import sys

from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/vic-guns'))
import m1a
import m16a1
import pngio

WORK = ROOT / 'tools/blockbench-models/m16-zomboid'
BASE = WORK / 'vic-m16-before.bbmodel'
TARGET = WORK / 'pz-m16-zomboid.bbmodel'
EVIDENCE = ROOT / 'release/evidence/20261017/m16-standalone'


def handguard():
    r = m1a.Rifle()
    # PZ's M16_Rifle.x silhouette, normalized to the donor's length/bore.
    r.slab('m16a1kit', 'guard', 3.78, (-60.0, 27.95), (-22.0, 28.35), 3.7, cut=1.05)
    r.slab('m16a1kit', 'guard', 3.76, (-60.0, 20.65), (-22.0, 20.0), 3.7, up=True, cut=1.05)
    r.beam('m16a1kit', 'steel', 3.82, 20.55, 28.0, -61.0, -59.9, cut=1.05)
    r.beam('m16a1kit', 'steel', 4.12, 19.75, 28.6, -22.1, -19.7, cut=1.15)
    scale = 125.35 / 109.8
    def point(v):
        return [v[0] * scale, 24.3 + (v[1] - 22.85) * scale, -88.05 + (v[2] + 74) * scale]
    # Use the project's measured PZ stock construction, with a uniform transform so
    # rotated pieces keep their shared edges closed. The donor receiver remains.
    for cube in m16a1.rifle_cubes():
        if cube['bone'] != 'gun' or cube['origin'][2] < 6 or cube['material'] not in ('guard', 'dark'):
            continue
        cube = copy.deepcopy(cube)
        cube['origin'] = point(cube['origin'])
        cube['size'] = [v * scale for v in cube['size']]
        if 'pivot' in cube:
            cube['pivot'] = point(cube['pivot'])
        if cube['material'] == 'guard':
            cube['material'] = 'stock'
        r.cubes.append(cube)
    # The ribs and vent slots are painted at consistent model-space spacing.
    return r.cubes


def main():
    model = json.loads(BASE.read_text(encoding='utf-8'))
    if TARGET.exists():
        raise SystemExit(f'Refusing to replace {TARGET}; inspect the existing revision first')
    groups = {g['uuid']: g for g in model['groups']}
    by_name = {g['name']: g for g in model['groups']}
    elements = {e['uuid']: e for e in model['elements']}
    members = {}
    ancestry = {}

    def walk(nodes, chain=()):
        for node in nodes:
            if isinstance(node, str):
                ancestry[node] = chain
                if chain:
                    members.setdefault(chain[-1], []).append(elements[node])
            else:
                name = groups[node['uuid']]['name']
                walk(node.get('children', []), chain + (name,))
    walk(model['outliner'])
    for e in members['magazine']:
        e['box_uv'] = False
    for e in members['gun'][78:95]:
        e['box_uv'] = False
    model['geckolib_modid'] = 'zombcraft_m16'
    TARGET.write_text(json.dumps(model, separators=(',', ':')), encoding='utf-8')

    ops = []
    edits = []
    # Remove dormant underbarrel/laser attachment geometry, retaining the rig nodes.
    unused = {'launcher', 'shotgun', 'lasercannon'}
    removed = set()
    for uid, chain in ancestry.items():
        if unused.intersection(chain):
            ops.append({'op': 'remove_node', 'target': uid})
            removed.add(uid)
    # Replace only the A1 handguard shell; preserve the detailed receiver/sights.
    for e in members['m16a1kit'][:19]:
        ops.append({'op': 'remove_node', 'target': e['uuid']})
        removed.add(e['uuid'])
    edits.append('A1 triangular shell replaced by PZ-style tapered round A2 handguard/cap/delta ring')

    stock = members['gun'][78:95]
    for e in stock:
        ops.append({'op': 'remove_node', 'target': e['uuid']})
        removed.add(e['uuid'])
    edits.append('Fixed stock rebuilt to the measured PZ profile, retaining shared edges and the butt endpoint')
    grip = members['gun'][4]
    ops.append({'op': 'update_node', 'target': grip['uuid'], 'rotation': [-29, 0, 0]})
    edits.append('Pistol grip rake reduced from 35 to 29 degrees')

    cubes = handguard()
    placed = m1a.pack(cubes)
    tones = ((28, 30, 32), (39, 42, 44), (52, 55, 57), (66, 70, 73), (81, 85, 89))
    materials = dict(m1a.MATERIALS)
    materials['guard'] = (tones, 2, False)
    materials['stock'] = (tones, 2, False)
    materials['steel'] = (m1a.STEEL, 2, False)

    def ribs(z, y):
        if not -59.0 < z < -23.0:
            return None
        phase = (z + 59) % 3.1
        if y > 26.8 and phase < .9:
            return (18, 20, 22)
        return tones[1 if phase < .65 else 3 if phase < 1.15 else 2]

    atlas = m1a.paint(cubes, placed, materials, {'guard': ribs})
    original = Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',')[1]))).convert('RGBA')
    pixels = np.array(original)
    # Lift neutral black furniture toward PZ's charcoal while retaining authored details.
    rgb = pixels[..., :3].astype(float)
    neutral = (rgb.max(2) - rgb.min(2) < 18) & (rgb.max(2) < 100) & (pixels[..., 3] > 0)
    rgb[neutral] = np.clip(rgb[neutral] * 1.08 + 8, 0, 255)
    pixels[..., :3] = rgb.astype(np.uint8)
    combined = np.zeros((512, 1024, 4), np.uint8)
    combined[:pixels.shape[0], :pixels.shape[1]] = pixels
    combined[:atlas.shape[0], 512:512 + atlas.shape[1]] = atlas
    # Independent magazine swatches; color pattern follows face dimensions.
    magazine = members['magazine']
    for i, e in enumerate(magazine):
        f, t = copy.deepcopy(e['from']), copy.deepcopy(e['to'])
        for v in (f, t):
            v[0] *= .9
            v[1] = 21.75 + (v[1] - 21.75) * .94
        face_uv = {}
        for j, face in enumerate(('north', 'east', 'south', 'west', 'up', 'down')):
            u, v = 790 + j * 34, 8 + i * 64
            w = max(1, round(abs((t[2] - f[2]) if face in ('east', 'west') else (t[0] - f[0])) * 2))
            h = max(1, round(abs((t[2] - f[2]) if face in ('up', 'down') else (t[1] - f[1])) * 2))
            w, h = min(w, 30), min(h, 58)
            for yy in range(h):
                for xx in range(w):
                    k = -10 if xx in (0, w - 1) or yy % 12 == 0 else 8 if xx % 8 == 1 else 0
                    combined[v + yy, u + xx] = (63 + k, 70 + k, 58 + k, 255)
            face_uv[face] = {'uv': [u, v, u + w, v + h], 'texture': model['textures'][0]['uuid']}
        # Explicit per-face coordinates avoid auto box UV undoing the material layout.
        ops.append({'op': 'update_node', 'target': e['uuid'], 'from': f, 'to': t, 'faces': face_uv})
    edits.append('Magazine shortened slightly, narrowed, and given PZ-like grey olive material')

    tex = model['textures'][0]
    data_url = 'data:image/png;base64,' + base64.b64encode(pngio.to_bytes(combined)).decode()
    ops.append({'op': 'update_texture', 'target': tex['uuid'], 'source': data_url, 'width': 1024, 'height': 512, 'material': None})
    ops.append({'op': 'set_model_properties', 'name': 'PZ M16 - Vic rig',
                'model_identifier': 'pz_m16', 'resolution': {'width': 1024, 'height': 512}})
    for i, c in enumerate(cubes):
        x, y, z = c['origin']; sx, sy, sz = c['size']
        # Native Blockbench coordinates mirror Bedrock X and rotation X/Y.
        faces = {}
        for face in m1a.FACES:
            u, v, w, h = placed[i, face]
            uv = [u + 512, v, u + 512 + w, v + h]
            if face in ('up', 'down'):
                uv = [uv[2], uv[3], uv[0], uv[1]]
            faces[face] = {'uv': uv, 'texture': tex['uuid']}
        rotation = c.get('rotation', [0, 0, 0])
        pivot = c.get('pivot', [0, 0, 0])
        ops.append({'op': 'add_cube', 'name': f'pz_a2_handguard_{i:02d}',
                    'from': [-x - sx, y, z], 'to': [-x, y + sy, z + sz],
                    'origin': [-pivot[0], pivot[1], pivot[2]],
                    'rotation': [-rotation[0], -rotation[1], rotation[2]],
                    'parent': by_name[c['bone']]['uuid'], 'box_uv': False, 'faces': faces})
    (EVIDENCE / 'edit-operations.json').write_text(json.dumps({'file': 'm16-zomboid/pz-m16-zomboid.bbmodel', 'operations': ops}), encoding='utf-8')
    (EVIDENCE / 'geometry-decisions.json').write_text(json.dumps({
        'priority': 'Match PZ closely', 'reference': 'M16_Rifle.x from owner install, measuring only',
        'retained': 'Vic receiver, sight detail, pivots, arm rig and 23 clips',
        'changes': edits, 'removed_cubes': len(removed), 'added_cubes': len(cubes),
        'texture_size': [1024, 512], 'baseline_cubes': len(elements),
        'estimated_final_cubes': len(elements) - len(removed) + len(cubes)}, indent=2), encoding='utf-8')
    print(f'Prepared {len(ops)} reversible headless operations; {len(elements) - len(removed) + len(cubes)} cubes')


if __name__ == '__main__':
    main()
