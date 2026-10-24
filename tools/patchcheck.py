"""Checks the PZ mod's ZombieBuddy patches (pz-mod, Patch_*.java) before a build goes to a game.

    python tools/patchcheck.py [--viewpoint VIEWPOINT_JAR --pz PROJECTZOMBOID_JAR] [--classes pz-mod/build/classes/java/main]

1. Access (always; reads the built classes, so build pz-mod first). ZombieBuddy copies an advice's code into the patched
   method, so the advice runs as part of PZ's or Viewpoint's class, in another package. Everything of PzCraft it touches must
   be public: a package-private class or member throws IllegalAccessError there (0.16.0: Patch_SteveThirdPerson called the
   package-private CharacterType.locksView() from viewpoint.camera.ThirdPerson.poll, on every frame, in IsoWorld.renderInternal).
   A lambda in an advice is reported too (its body is a private method of the patch class).
2. Targets (with both jars): the patched class and method exist, and @Argument(n) indices fit the method's parameters.

Exit status 1 when a problem is found.
"""
import argparse
import os
import re
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACC_PUBLIC = 0x1


# ---------------------------------------------------------------- class files

def parse_class(data):
    """The parts of a class file this check needs: constant pool, access flags, name, fields, methods (with attributes)."""
    count = struct.unpack('>H', data[8:10])[0]
    cp, pos, i = [None] * count, 10, 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack('>H', data[pos + 1:pos + 3])[0]
            cp[i] = ('utf8', data[pos + 3:pos + 3 + n].decode('utf-8', 'replace'))
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag == 7:
            cp[i] = ('class', struct.unpack('>H', data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11):
            c, nt = struct.unpack('>HH', data[pos + 1:pos + 5])
            cp[i] = ({9: 'field', 10: 'method', 11: 'imethod'}[tag], c, nt)
            pos += 5
        elif tag == 12:
            cp[i] = ('nat',) + struct.unpack('>HH', data[pos + 1:pos + 5])
            pos += 5
        elif tag == 15:
            pos += 4
        elif tag in (17, 18):
            cp[i] = ('indy',)
            pos += 5
        else:
            raise ValueError(f'constant pool tag {tag}')
        i += 1
    access, this_c = struct.unpack('>HH', data[pos:pos + 4])
    pos += 6
    pos += 2 + 2 * struct.unpack('>H', data[pos:pos + 2])[0]

    def members(pos):
        """A field or method table at pos: [(access, name, descriptor, {attribute: bytes})] and the position after it."""
        out = []
        n = struct.unpack('>H', data[pos:pos + 2])[0]
        pos += 2
        for _ in range(n):
            acc, name, desc, attr_count = struct.unpack('>HHHH', data[pos:pos + 8])
            pos += 8
            attrs = {}
            for _ in range(attr_count):
                an, length = struct.unpack('>HI', data[pos:pos + 6])
                attrs[cp[an][1]] = data[pos + 6:pos + 6 + length]
                pos += 6 + length
            out.append((acc, cp[name][1], cp[desc][1], attrs))
        return out, pos

    fields, pos = members(pos)
    methods, pos = members(pos)
    return {'cp': cp, 'access': access, 'name': cp[cp[this_c][1]][1], 'fields': fields, 'methods': methods}


# opcode -> instruction length, for the ones that are not one byte (switches and wide are handled apart)
LENGTHS = {0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 2, 0x18: 2, 0x19: 2, 0x36: 2, 0x37: 2,
           0x38: 2, 0x39: 2, 0x3a: 2, 0x84: 3, 0xa9: 2, 0xb9: 5, 0xba: 5, 0xbc: 2, 0xc5: 4, 0xc8: 5, 0xc9: 5,
           **{op: 3 for op in range(0x99, 0xa9)}, **{op: 3 for op in range(0xb2, 0xb9)}, 0xbb: 3, 0xbd: 3, 0xc0: 3,
           0xc1: 3, 0xc6: 3, 0xc7: 3}
POOL_REFS = {0x12, 0x13, 0x14, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbd, 0xc0, 0xc1, 0xc5}


def pool_refs(code):
    """The constant pool entries a method's bytecode uses."""
    n = struct.unpack('>I', code[4:8])[0]
    b, i, out = code[8:8 + n], 0, []
    while i < len(b):
        op = b[i]
        if op in (0xaa, 0xab):
            j = i + 1 + (-(i + 1) % 4)
            if op == 0xaa:
                lo, hi = struct.unpack('>ii', b[j + 4:j + 12])
                i = j + 12 + 4 * (hi - lo + 1)
            else:
                i = j + 8 + 8 * struct.unpack('>i', b[j + 4:j + 8])[0]
            continue
        if op == 0xc4:
            i += 6 if b[i + 1] == 0x84 else 4
            continue
        if op in POOL_REFS:
            out.append(b[i + 1] if op == 0x12 else struct.unpack('>H', b[i + 1:i + 3])[0])
        i += LENGTHS.get(op, 1)
    return out


def check_access(classes_dir):
    classes = {}
    for f in Path(classes_dir).rglob('*.class'):
        c = parse_class(f.read_bytes())
        classes[c['name']] = c
    patches = [c for c in classes.values()
               if any(e and e[0] == 'utf8' and e[1].endswith('zombie_buddy/Patch;') for e in c['cp'])]
    problems = []
    for c in sorted(patches, key=lambda c: c['name']):
        cp = c['cp']
        for _, method, _, attrs in c['methods']:
            if method in ('<init>', '<clinit>') or 'Code' not in attrs:
                continue
            where = f"{c['name'].rsplit('/', 1)[-1]}.{method}"
            for k in pool_refs(attrs['Code']):
                e = cp[k]
                if e is None:
                    continue
                if e[0] == 'indy':
                    problems.append(f'{where}: a lambda or invokedynamic (its target is private to the patch class)')
                    continue
                if e[0] == 'class':
                    owner, member = cp[e[1]][1], None
                elif e[0] in ('field', 'method', 'imethod'):
                    owner = cp[cp[e[1]][1]][1]
                    nat = cp[e[2]]
                    member = (cp[nat[1]][1], cp[nat[2]][1], e[0])
                else:
                    continue
                owner = owner.lstrip('[').removeprefix('L').removesuffix(';')
                target = classes.get(owner)
                if target is None:
                    continue                    # PZ, Viewpoint, ZombieBuddy, the JDK
                if not target['access'] & ACC_PUBLIC:
                    problems.append(f'{where}: class {owner} is not public')
                if member:
                    name, desc, kind = member
                    pool = target['fields'] if kind == 'field' else target['methods']
                    found = [m for m in pool if m[1] == name and (kind == 'field' or m[2] == desc)]
                    if found and not found[0][0] & ACC_PUBLIC:
                        problems.append(f'{where}: {owner.rsplit("/", 1)[-1]}.{name} is not public')
    problems = sorted(set(problems))
    for p in problems:
        print('ACCESS  ', p)
    print(f'access: {len(patches)} patch classes, {len(problems)} problems')
    return len(problems)


# ---------------------------------------------------------------- targets

def javap():
    home = os.environ.get('JAVA_HOME')
    return str(Path(home) / 'bin' / 'javap') if home else 'javap'


def check_targets(viewpoint_jar, pz_jar):
    cache, bad, count = {}, 0, 0

    def members(cls):
        if cls not in cache:
            jar = viewpoint_jar if cls.startswith('viewpoint.') else pz_jar
            cache[cls] = subprocess.run([javap(), '-p', '-cp', jar, cls], capture_output=True, text=True).stdout
        return cache[cls]
    for f in sorted((ROOT / 'pz-mod/src/main/java/pzcraft/pz').glob('Patch_*.java')):
        src = f.read_text(encoding='utf-8')
        m = re.search(r'@Patch\(\s*className\s*=\s*"([^"]+)"\s*,\s*methodName\s*=\s*"([^"]+)"', src)
        if not m:
            continue
        count += 1
        cls, method = m.groups()
        out = members(cls)
        if not out:
            print(f'TARGET   {f.name}: class {cls} not found')
            bad += 1
            continue
        sigs = [line.strip() for line in out.splitlines() if re.search(r'[ .]' + re.escape(method) + r'\(', line)]
        if not sigs:
            print(f'TARGET   {f.name}: {cls}.{method} not found')
            bad += 1
            continue
        args = [int(a) for a in re.findall(r'@Argument\((?:value\s*=\s*)?(\d+)', src)]
        need = max(args) + 1 if args else 0
        counts = [0 if not s[s.index('(') + 1:s.rindex(')')].strip() else s[s.index('(') + 1:s.rindex(')')].count(',') + 1 for s in sigs]
        if not any(c >= need for c in counts):
            print(f'TARGET   {f.name}: {cls}.{method} has no overload with {need} parameters')
            bad += 1
    print(f'targets: {count} patches, {bad} problems')
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--classes', default=str(ROOT / 'pz-mod/build/classes/java/main'))
    ap.add_argument('--viewpoint', help="Viewpoint's jar (42/media/java/client/Viewpoint.jar)")
    ap.add_argument('--pz', help="PZ's projectzomboid.jar")
    a = ap.parse_args()
    bad = check_access(a.classes)
    if a.viewpoint and a.pz:
        bad += check_targets(a.viewpoint, a.pz)
    else:
        print('targets: not checked (give --viewpoint and --pz)')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
