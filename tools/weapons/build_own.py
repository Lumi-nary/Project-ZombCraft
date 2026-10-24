"""Build Project-ZombCraft's original bundled gun models.

    python tools/weapons/build_own.py [--out fabric-mod/src/main/resources/pzcraft/guns] [--kinds m1a,js14,trapper,m9]

Geometry, textures and base clips are authored in the model and *_anim modules.
Shared helpers derive rack, unload, empty/no-magazine, breathing and magazine-feed
clips. Only these original assets enter the bundled manifest; no external visual
sets are imported. --kinds optionally selects a subset and its magazines.
"""
import argparse
import copy
import hashlib
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build  # noqa: E402

PROJECT = HERE.parents[1]
SKELETON = HERE / 'rifle_skeleton.json'
# kind -> (PZ types, what the manifest says about it besides its clips)
KINDS = {
    'm1a': (['Base.AssaultRifle2'], {'shell': 'rifle', 'made': True, 'own': True}),
    'js14': (['Base.JS14_Rifle'], {'shell': 'rifle', 'made': True, 'own': True}),
    'trapper': (['Base.TrapperCarbine'], {'shell': 'pistol', 'made': True, 'own': True}),
    'm9': (['Base.Pistol'], {'pistol': True, 'shell': 'pistol', 'made': True, 'own': True}),
    'm1911a1': (['Base.Pistol2'], {'pistol': True, 'shell': 'pistol', 'made': True, 'own': True}),
    'deserteagle': (['Base.Pistol3'], {'pistol': True, 'shell': 'pistol', 'made': True, 'own': True}),
    'm16a1': (['Base.AssaultRifle'], {'shell': 'rifle', 'made': True, 'own': True}),
}
# a magazine held by itself -> the gun of ours it is made from (that module's magazine_spec)
MAGAZINES = {'mag9mm': 'm9', 'mag45': 'm1911a1', 'mag44': 'deserteagle', 'mag556': 'm16a1'}


# the name build.py's recipes are given for a gun of ours: never one of the the reference set guns its tables single out (the M9's
# hand rack, the M16's charging handle), which a kind of ours may share a name with
OWN = 'own_'


class OwnSource:
    """What build.py's recipes read from a gun, for a gun of our own: its model, its clips (the <kind>_anim module) and its
    data (that module's DATA). There are no shared clips: every clip the recipe needs is the module's own."""
    def __init__(self, kind, geo):
        self.kind, self.geo = kind, geo
        self.module = __import__(kind + '_anim')
        self.made = None

    def json(self, path):
        if path.endswith((f'/{self.kind}.geo.json', f'/{OWN}{self.kind}.geo.json')):
            return copy.deepcopy(self.geo)
        if path.endswith(('/common.animation.json', '/pistol.animation.json')):
            return {'animations': {}}
        raise KeyError(f'{self.kind}: an own gun has no {path}')

    def data(self, gun):
        return copy.deepcopy(self.module.DATA)

    def clips(self, gun):
        if self.made is None:
            self.made = self.module.clips(self.geo)
        return copy.deepcopy(self.made)


def build_own(out, only=None):
    rifles = json.loads(SKELETON.read_text())
    if out.exists():
        shutil.rmtree(out)
    assets = out / 'assets' / 'pzcraft'
    files, kinds, types = {}, {}, {}

    def put(rel, data):
        target = assets / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, (dict, list)):
            data = json.dumps(data, separators=(',', ':')).encode()
        target.write_bytes(data)
        files[rel] = hashlib.sha256(data).hexdigest()

    def finish(kind, geo, png, base, clips, info, extra, data, pz_types, recipe, cues):
        near = build.held_at(geo, base) / build.BREATH_AT
        clips = {k: build.breathing(v, near) if k.startswith('idle') else v for k, v in clips.items()}
        name = 'gun_' + kind
        put(f'geckolib/models/item/{name}.geo.json', geo)
        put(f'geckolib/animations/item/{name}.animation.json',
            {'format_version': '1.8.0', 'animations': {build.PREFIX + k: build.in_order(v) for k, v in clips.items()}})
        put(f'textures/item/{name}.png', png)
        put(f'models/item/{name}_base.json', base)
        bones = [b['name'] for b in geo['minecraft:geometry'][0]['bones']]
        entry = {'source': 'own', 'recipe': recipe, 'hide': build.default_hidden(geo, data),
                 'clips': {k: float(v['animation_length']) for k, v in clips.items()},
                 'bones': {k: k in bones for k in ('magazine', 'magazine2', '_shelleject_', 'scope', 'muzzleflash', 'rightarm', 'leftarm')}}
        entry.update(extra)
        entry.update(info)
        if 'viewRecoilAmplitude' in data:
            entry['viewRecoil'] = data['viewRecoilAmplitude']
        if cues:
            entry['cues'] = cues
        kinds[kind] = entry
        for t in pz_types:
            types[t] = {'kind': kind}

    made = {}
    for kind, (pz_types, extra) in KINDS.items():
        if only and kind not in only:
            continue
        model = __import__(kind)
        skeleton = model.skeleton() if hasattr(model, 'skeleton') else rifles
        # the GeckoLib model draws the gun; the base still needs a particle texture or Minecraft logs it as missing
        base = {'gui_light': 'front', 'textures': {'particle': build.PARTICLE}, 'display': skeleton['display']}
        geo = model.geometry(skeleton)
        source = OwnSource(kind, geo)
        data = source.data(kind)
        clips, info = build.recipe_magazine(source, OWN + kind, extra.get('pistol', False))
        nomag_empty = getattr(source.module, 'NOMAG_EMPTY', None)
        if nomag_empty:                 # the empty reload with no magazine in the gun made from another clip of the gun's own
            name, phase = nomag_empty
            own = source.clips(kind)
            bare = build.reload_without_magazine(build.plain(own[name]), phase, build.sound_cues(own, (name,)).get(name, []))
            if bare is not None:
                clips['reloademptynomag'], info['reloadEmptyNoMagPhase'], info.setdefault('cues', {})['reloademptynomag'] = bare
        png = model.texture()
        cues = build.sound_cues(source.clips(kind), tuple(n for n in ('reload', 'reloadempty', 'inspect', 'inspectempty') if n in clips))
        cues.update(info.get('cues', {}))
        finish(kind, geo, png, base, clips, info, extra, data, pz_types, 'magazine', cues)
        made[kind] = (model, source, base, png)
    for kind, gun in MAGAZINES.items():
        if gun not in made:
            continue
        model, source, base, png = made[gun]
        spec = model.magazine_spec(png)
        view = spec.get('view', build.MAGAZINE_VIEW)
        geo = build.magazine_item_geo(source.geo, spec)
        clips, info = build.recipe_feed(source, OWN + gun, spec)
        base = dict(base, display=dict(base['display'], firstperson_righthand=view))     # held up where its mouth is looked into
        finish(kind, geo, build.paint_rounds(png, spec), base, clips, info, {'made': True, 'own': True, 'feed': kind, 'firstPerson': view}, {}, [spec['type']], 'feed', {})
    manifest = {'version': 1, 'source': 'own', 'kinds': kinds, 'types': types, 'files': files}
    (out / 'manifest.json').write_bytes(json.dumps(manifest, indent=1).encode('utf-8'))
    print(f'{len(kinds)} kinds, {len(types)} PZ types, {len(files)} files -> {out}')
    for kind, e in kinds.items():
        print(f"  {kind:8s} hide={e['hide']}  clips=" + ' '.join(f'{k}:{v:.2f}' for k, v in e['clips'].items()))
    # The owner's canonical edited M16 must survive procedural regeneration.
    import importlib.util
    integration = PROJECT / 'tools/m16-zomboid/integrate.py'
    spec = importlib.util.spec_from_file_location('edited_m16_integration', integration)
    edited_m16 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(edited_m16)
    return edited_m16.apply_to(out)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=str(PROJECT / 'fabric-mod' / 'src' / 'main' / 'resources' / 'pzcraft' / 'guns'))
    ap.add_argument('--kinds', help='only these kinds, comma-separated (default: all)')
    a = ap.parse_args()
    build_own(Path(a.out), set(a.kinds.split(',')) if a.kinds else None)
