"""Convert the weapon models PZ itself uses (media/models_X/*.x + media/textures) into private data for the Minecraft side.

  python build-weapon-models.py          (reads the game install read-only; writes ~/.pzcraft/items/weapon-models/)

Output (private, never inside the project or a jar): index.json {"items": {fullType: modelKey}, "models": {key: {...}}},
models/<key>.json (triangles: pos, uv, smooth normals, raw .x coordinates), textures/<key>.png, display.json (hand
transforms, written once and then yours to edit: the Minecraft side rebuilds its resource pack when it changes).
Weapons whose mesh is not a text .x file (fbx) or has no model script keep their PZ sprite.
"""
import json
import math
import shutil
from pathlib import Path
import wm_parse
import xmesh

MEDIA = wm_parse.MEDIA
OUT = Path.home() / '.pzcraft' / 'items' / 'weapon-models'
SKIP_ITEMS = set()  # every weapon needs a PZ mesh fallback, including custom-model weapons
BAD = {'null', 'none', ''}


def texture_for(model, mesh_path, x_texture):
    cands = []
    if model.get('texture') and model['texture'].lower() not in BAD:
        cands.append(MEDIA / 'textures' / (model['texture'] + '.png'))
    cands.append(MEDIA / 'textures' / (model['mesh'] + '.png'))
    if x_texture:
        cands += [MEDIA / 'textures' / Path(model['mesh']).parent / x_texture, MEDIA / 'textures' / x_texture]
    for c in cands:
        if c.is_file():
            return c
    if x_texture:
        hit = next(iter((MEDIA / 'textures').rglob(x_texture)), None)
        if hit: return hit
    return None


def x_texture_name(path):
    import re
    m = re.search(r'TextureFilename\s*\{\s*"([^"]+)"', Path(path).read_text(encoding='latin-1'))
    return m.group(1) if m else None


def convert(mesh_file):
    verts, uvs, faces = [], [], []
    for m in xmesh.read(mesh_file):
        base = len(verts)
        verts += m['vertices']
        uvs += m['uvs'] if m['uvs'] else [(0.0, 0.0)] * len(m['vertices'])
        for f in m['faces']:
            for i in range(1, len(f) - 1):
                faces.append((base + f[0], base + f[i], base + f[i + 1]))
    nrm = [[0.0, 0.0, 0.0] for _ in verts]
    for a, b, c in faces:
        pa, pb, pc = verts[a], verts[b], verts[c]
        u = [pb[k] - pa[k] for k in range(3)]; v = [pc[k] - pa[k] for k in range(3)]
        n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
        for i in (a, b, c):
            for k in range(3): nrm[i][k] += n[k]
    for n in nrm:
        d = math.sqrt(sum(x * x for x in n)) or 1.0
        n[:] = [x / d for x in n]
    pos, uv, nr = [], [], []
    for tri in faces:
        for i in tri:
            pos += [round(x, 5) for x in verts[i]]; uv += [round(uvs[i][0], 5), round(uvs[i][1], 5)]; nr += [round(x, 4) for x in nrm[i]]
    if not verts or not faces: return None
    xs, ys, zs = zip(*verts)
    return {'tris': len(faces), 'pos': pos, 'uv': uv, 'nrm': nr, 'min': [min(xs), min(ys), min(zs)], 'max': [max(xs), max(ys), max(zs)]}


def main():
    items, models = wm_parse.items(), wm_parse.models()
    OUT.joinpath('models').mkdir(parents=True, exist_ok=True)
    OUT.joinpath('textures').mkdir(parents=True, exist_ok=True)
    index = {'version': 1, 'items': {}, 'models': {}}
    skipped = {}
    for full, it in sorted(items.items()):
        if full in SKIP_ITEMS: continue
        name = full.split('.', 1)[1]
        sprite = it['sprite'] if it['sprite'] and it['sprite'].lower() not in BAD else (it['sprites'][0] if it['sprites'] else None)
        if not sprite and name in models: sprite = name
        if not sprite or sprite not in models:
            skipped[full] = f'no model script ({sprite})'; continue
        key = sprite.lower()
        if key not in index['models']:
            m = models[sprite]
            mesh_file = next((p for p in (MEDIA / 'models_X' / (m['mesh'] + '.x'), MEDIA / 'models_X' / (m['mesh'] + '.X')) if p.is_file()), None)
            if not mesh_file:
                skipped[full] = f"mesh {m['mesh']} is not a .x file"; continue
            tex = texture_for(m, mesh_file, x_texture_name(mesh_file))
            if not tex:
                skipped[full] = f"no texture for {m['mesh']}"; continue
            try: data = convert(mesh_file)
            except Exception as e: data = None; skipped[full] = f'mesh {m["mesh"]}: {type(e).__name__} {e}'; continue
            if data is None: skipped[full] = f'mesh {m["mesh"]} has no geometry'; continue
            (OUT / 'models' / f'{key}.json').write_text(json.dumps(data, separators=(',', ':')))
            shutil.copyfile(tex, OUT / 'textures' / f'{key}.png')
            att = m['attachments']
            hand = att.get('Bip01_Prop2') or att.get('Bip01_Prop1') or {'offset': [0, 0, 0], 'rotate': [0, 0, 0], 'scale': 1.0}
            index['models'][key] = {'sprite': sprite, 'mesh': m['mesh'], 'texture': str(tex.relative_to(MEDIA)).replace('\\', '/'),
                                    'scale': m['scale'], 'hand': hand, 'tris': data['tris'], 'min': data['min'], 'max': data['max']}
        index['items'][full] = key
    (OUT / 'index.json').write_text(json.dumps(index, indent=1))
    display = OUT / 'display.json'
    if not display.exists():
        display.write_text(json.dumps({
            'scale': 1.8, 'x': 0.0, 'y': 0.0, 'z': 0.0, 'rx': 0.0, 'ry': 0.0, 'rz': 0.0, 'flipX': 1, 'flipZ': -1, 'useAttachment': 0,
            'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
            'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
        }, indent=1))
    print(f"{len(index['items'])} weapons -> {len(index['models'])} models in {OUT}; skipped {len(skipped)}")
    for k, v in list(skipped.items())[:40]: print('  skipped', k, v)
    from pz_weapon_motion import build
    build(MEDIA, OUT / 'motion.json')


if __name__ == '__main__':
    main()
