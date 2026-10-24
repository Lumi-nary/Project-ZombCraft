"""Parse PZ weapon item scripts and model scripts (read-only, from the game install)."""
import os
import re
from pathlib import Path

MEDIA = Path(os.environ.get('PZCRAFT_PZDIR', r'E:/SteamLibrary/steamapps/common/ProjectZomboid')) / 'media'


def blocks(text, keyword):
    """Yield (name, body) for each `keyword NAME { ... }` with balanced braces (nested blocks stay inside body)."""
    for m in re.finditer(r'(?m)^[ \t]*' + keyword + r'[ \t]+([\w.]+)[ \t\r\n]*\{', text):
        depth, i = 1, m.end()
        while depth and i < len(text):
            depth += (text[i] == '{') - (text[i] == '}'); i += 1
        yield m.group(1), text[m.end():i - 1]


def items():
    """{fulltype: {'sprite': str|None, 'sprites': [..], 'two': bool}} for Base weapons."""
    out = {}
    text = (MEDIA / 'scripts/generated/items/weapon.txt').read_text(encoding='utf-8', errors='replace')
    for name, body in blocks(text, 'item'):
        if 'ItemType = base:weapon' not in body: continue
        sp = re.search(r'WeaponSprite\s*=\s*([^,\n]+)', body)
        idx = re.search(r'WeaponSpritesByIndex\s*=\s*([^,\n]+)', body)
        out['Base.' + name] = {'sprite': sp.group(1).strip() if sp else None,
                               'sprites': [s.strip() for s in idx.group(1).split(';')] if idx else [],
                               'two': bool(re.search(r'TwoHandWeapon\s*=\s*true', body)),
                               'icon': (re.search(r'\bIcon\s*=\s*([^,\n]+)', body) or [None, None])[1]}
    return out


def models():
    """{name: {'mesh', 'texture', 'scale', 'attachments': {name: {'offset':[3], 'rotate':[3], 'scale': f}}}}"""
    out = {}
    for f in sorted((MEDIA / 'scripts/generated').glob('models_*.txt')):
        text = f.read_text(encoding='utf-8', errors='replace')
        for name, body in blocks(text, 'model'):
            att = {}
            for an, ab in blocks(body, 'attachment'):
                o = re.search(r'offset\s*=\s*([-\d.eE ]+)', ab); r = re.search(r'rotate\s*=\s*([-\d.eE ]+)', ab); s = re.search(r'scale\s*=\s*([-\d.eE]+)', ab)
                att[an] = {'offset': [float(x) for x in o.group(1).split()] if o else [0, 0, 0],
                           'rotate': [float(x) for x in r.group(1).split()] if r else [0, 0, 0],
                           'scale': float(s.group(1)) if s else 1.0}
            head = re.sub(r'attachment[ \t]+\w+[ \t\r\n]*\{[^{}]*\}', '', body)
            mesh = re.search(r'mesh\s*=\s*([^,\n]+)', head); tex = re.search(r'texture\s*=\s*([^,\n]+)', head); sc = re.search(r'\bscale\s*=\s*([-\d.eE]+)', head)
            if mesh: out[name] = {'mesh': mesh.group(1).strip(), 'texture': tex.group(1).strip() if tex else None,
                                  'scale': float(sc.group(1)) if sc else 1.0, 'attachments': att, 'file': f.name}
    return out


if __name__ == '__main__':
    it, md = items(), models()
    print(len(it), 'weapons;', len(md), 'models')
    nosprite = [k for k, v in it.items() if not v['sprite']]
    missing = [(k, v['sprite']) for k, v in it.items() if v['sprite'] and v['sprite'] not in md]
    print('no WeaponSprite:', len(nosprite), nosprite[:8]); print('sprite without model script:', len(missing), missing[:8])
    import json
    cat = {x['id']: x for x in json.load(open(Path.home() / '.pzcraft/items/items.json'))['items']}
    by = {}
    for k, v in it.items():
        icon = cat.get(k, {}).get('icon')
        by.setdefault(icon, set()).add(v['sprite'])
    print('shared icons with different sprites:', {k: sorted(map(str, v)) for k, v in by.items() if len(v) > 1})
    meshfiles = lambda m: [p for p in [MEDIA / 'models_X' / (m['mesh'] + '.x'), MEDIA / 'models_X' / (m['mesh'] + '.X')] if p.is_file()]
    nomesh = [(k, md[v['sprite']]['mesh']) for k, v in it.items() if v['sprite'] in md and not meshfiles(md[v['sprite']])]
    print('meshes that are not .x files:', len(nomesh), nomesh[:10])
