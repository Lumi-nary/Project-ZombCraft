"""Retarget PZ's local firearm hand tracks to generic held-weapon motion.

Reads the owner's game files; converted animation data stays in ~/.pzcraft/items,
never in the mod JAR. NumPy is needed only for this local conversion.
"""
import hashlib
import json
import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import wm_parse
from xmesh import _block

PROFILES = {
    'handgun': 'Handgun', 'revolver': 'Revolver', 'boltaction': 'Rifle',
    'boltactionnomag': 'RifleNoMag', 'shotgun': 'Shotgun',
    'doublebarrelshotgun': 'DblBarrel', 'doublebarrelshotgunsawn': 'DblBarrelSawnoff',
    'leveraction': 'LeverAction',
}
MELEE = {'1handed': '1handed/1HDefault', '2handed': '2handed/2HDefault',
         'knife': '1handed/KnifeDefault', 'spear': '2handed/SpearDefault',
         'heavy': 'heavy/HeavyDefault'}


def anim_node(media, name, seen=()):
    path = media / 'AnimSets/player/actions' / (name + '.xml')
    if name in seen:
        raise ValueError(f'Animation inheritance cycle: {name}')
    node = ET.parse(path).getroot()
    parent = node.get('x_extends')
    inherited = anim_node(media, Path(parent).stem, (*seen, name)) if parent else (None, False)
    value = node.findtext('m_AnimName') or inherited[0]
    if not value:
        raise ValueError(f'No animation in {path}')
    reverse = node.findtext('m_AnimReverse')
    return value, reverse.lower() == 'true' if reverse is not None else inherited[1]


def parse(path):
    text = path.read_text(encoding='latin-1')
    ticks = re.search(r'(?m)^AnimTicksPerSecond\s*\{\s*(\d+);', text)
    rate = int(ticks.group(1)) if ticks else 4800
    parents, defaults, stack = {}, {}, []
    for match in re.finditer(r'\bFrame\s+(\w+)\s*\{', text):
        while stack and stack[-1][1] < match.start():
            stack.pop()
        body = _block(text, match.end())
        name = match.group(1)
        parents[name] = stack[-1][0] if stack else None
        matrix = re.match(r'\s*FrameTransformMatrix\s*\{([^}]+)', body)
        if matrix:
            values = [float(v) for v in re.findall(r'-?\d+\.?\d*(?:[eE][-+]?\d+)?', matrix.group(1))]
            defaults[name] = np.array(values).reshape(4, 4).T
        stack.append((name, match.end() + len(body)))
    tracks, duration = {}, 0
    for match in re.finditer(r'(?m)^\s*Animation\s*\{\s*\{\s*(\w+)\s*\}', text):
        # Rewind to the outer opening brace, before the target bone reference.
        start = text.index('{', match.start()) + 1
        body = _block(text, start)
        channels = {}
        for key in re.finditer(r'AnimationKey\s*\w*\s*\{([^}]+)\}', body):
            kind = int(key.group(1).split(';', 1)[0])
            keys = []
            for record in re.finditer(r'(\d+);(\d+);([^;]+);;', key.group(1)):
                tick, size = int(record[1]), int(record[2])
                vector = [float(v) for v in record[3].split(',')]
                if len(vector) != size:
                    raise ValueError('Malformed animation key')
                keys.append((tick / rate, np.array(vector)))
                duration = max(duration, tick / rate)
            if keys:
                channels[kind] = keys
        tracks[match[1]] = channels
    if 'Bip01_R_Hand' not in tracks or duration <= 0:
        raise ValueError(f'No usable hand motion: {path}')
    return parents, defaults, tracks, duration


def sample(keys, t, quaternion=False):
    if t <= keys[0][0]:
        return keys[0][1].copy()
    for (a, v), (b, w) in zip(keys, keys[1:]):
        if t <= b:
            if quaternion and np.dot(v, w) < 0:
                w = -w
            return v + (w - v) * ((t - a) / (b - a))
    return keys[-1][1].copy()


def rotation(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def hand_pose(parsed, time, target='Bip01_R_Hand'):
    parents, defaults, tracks, _ = parsed
    chain, bone = [], target
    while bone is not None:
        chain.append(bone)
        bone = parents[bone]
    world = np.eye(4)
    for bone in reversed(chain):
        channels = tracks.get(bone, {})
        local = defaults.get(bone, np.eye(4)).copy()
        if 0 in channels:
            local[:3, :3] = rotation(sample(channels[0], time, True))
        if 1 in channels:
            local[:3, :3] *= sample(channels[1], time)
        if 2 in channels:
            local[:3, 3] = sample(channels[2], time)
        world = world @ local
    return world


def convert(path, reverse=False, prop='Bip01_Prop2'):
    parsed = parse(path)
    duration = parsed[3]
    start = duration if reverse else 0
    origin = np.linalg.inv(hand_pose(parsed, start, prop))
    initial_hands = [((origin @ hand_pose(parsed, start, bone))[:3, 3])
                     for bone in ('Bip01_R_Hand', 'Bip01_L_Hand')]
    frames = []
    hands = []
    previous = np.zeros(3)
    limited_angles, limited_position = np.zeros(3), np.zeros(3)
    count = max(3, math.ceil(duration * 60) + 1)
    step = duration / (count - 1)
    for time in np.linspace(0, duration, count):
        source_time = duration - time if reverse else time
        weapon = hand_pose(parsed, source_time, prop)
        delta = origin @ weapon
        local = np.linalg.inv(weapon)
        offsets = [np.clip((local @ hand_pose(parsed, source_time, bone))[:3, 3] - neutral, -.4, .4)
                   for bone, neutral in zip(('Bip01_R_Hand', 'Bip01_L_Hand'), initial_hands)]
        hands.append([round(time / duration, 6), *np.round(offsets[0], 6), *np.round(offsets[1], 6)])
        r = delta[:3, :3]
        # Camera-space adaptation about the grip; limit motion for first-person framing.
        angles = np.degrees([math.atan2(r[2, 1], r[2, 2]),
                             math.atan2(-r[2, 0], math.hypot(r[0, 0], r[1, 0])),
                             math.atan2(r[1, 0], r[0, 0])])
        # XYZ Euler rotations have two equivalent representations. Follow the
        # nearest one continuously so a melee swing crossing 180 degrees cannot snap.
        alternatives = [angles, np.array([angles[0] + 180, 180 - angles[1], angles[2] + 180])]
        alternatives = [previous + (a - previous + 180) % 360 - 180 for a in alternatives]
        angles = min(alternatives, key=lambda a: np.linalg.norm(a - previous))
        previous = angles.copy()
        translation = np.clip(delta[:3, 3], -.30, .30)
        angles = np.clip(angles, -65, 65)
        # PZ's third-person strikes can turn very quickly. Bound camera-space
        # adaptation speed while preserving the order and timing of the motion.
        limited_angles += np.clip(angles - limited_angles, -720 * step, 720 * step)
        limited_position += np.clip(translation - limited_position, -2.5 * step, 2.5 * step)
        frames.append([round(time / duration, 6), *np.round(limited_position, 6), *np.round(limited_angles, 4)])
    return {'seconds': duration, 'source': path.name, 'reverse': reverse, 'prop': prop,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'frames': frames, 'hands': hands}


def build(media=None, out=None):
    media = Path(media or wm_parse.MEDIA)
    out = Path(out or Path.home() / '.pzcraft/items/weapon-models/motion.json')
    cache, profiles = {}, {}
    for profile, suffix in PROFILES.items():
        actions = {}
        for action, prefix in [('reload', 'Load'), ('load', 'Load'), ('rack', 'Rack'), ('unload', 'Unload')]:
            animation, reverse = anim_node(media, prefix + suffix)
            path = media / 'anims_X/Bob' / (animation + '.x')
            prop = 'Bip01_Prop1' if profile in ('handgun', 'revolver') else 'Bip01_Prop2'
            key = animation + ('@reverse' if reverse else '')
            if key not in cache:
                cache[key] = convert(path, reverse, prop)
            actions[action] = key
        actions['reloadempty'] = actions['reload']
        profiles[profile] = actions
    for profile, node in MELEE.items():
        path = media / 'AnimSets/player/melee' / (node + '.xml')
        animation = ET.parse(path).getroot().findtext('m_AnimName')
        if not animation:
            raise ValueError(f'No melee animation in {path}')
        cache[animation] = convert(media / 'anims_X/Bob' / (animation + '.x'), prop='Bip01_R_Hand')
        profiles[profile] = {'attack': animation}
    result = {'version': 1, 'retarget': 'weapon-prop-and-two-hands', 'profiles': profiles, 'clips': cache}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(json.dumps(result, separators=(',', ':')).encode())
    print(f'PZ generic motion: {len(profiles)} profiles, {len(cache)} local clips -> {out}')
    return result


if __name__ == '__main__':
    build()
