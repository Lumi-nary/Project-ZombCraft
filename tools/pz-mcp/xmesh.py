"""Minimal reader for DirectX text .x meshes (the format of PZ's media/models_X weapons): vertices, faces, UVs, the frame matrix.

Used for private, local conversion only: the game's own files are read where they are and never copied into the project.
  python xmesh.py FILE.x ...   prints a summary per mesh
"""
import re
from pathlib import Path

NUM = re.compile(r'-?\d+\.?\d*(?:[eE][-+]?\d+)?')


def _block(text, start):
    """The text between the braces that open at text[start-1]."""
    depth, i = 1, start
    while depth and i < len(text):
        depth += (text[i] == '{') - (text[i] == '}')
        i += 1
    return text[start:i - 1]


def read(path):
    text = Path(path).read_text(encoding='latin-1')
    meshes = []
    for m in re.finditer(r'(?<![A-Za-z])Mesh[ \t]+([\w.-]*)[ \t\r\n]*\{', text):
        if text[max(0, m.start() - 9):m.start()] == 'template ':
            continue
        body = _block(text, m.end())
        # the mesh's own data comes before its nested blocks (MeshNormals, MeshTextureCoords, MeshMaterialList)
        own = body.split('MeshNormals')[0].split('MeshTextureCoords')[0].split('MeshMaterialList')[0]
        vals = [float(x) for x in NUM.findall(own)]
        nv = int(vals[0])
        verts = [tuple(vals[1 + k * 3:4 + k * 3]) for k in range(nv)]
        k = 1 + nv * 3
        nf = int(vals[k]); k += 1
        faces = []
        for _ in range(nf):
            n = int(vals[k]); faces.append([int(v) for v in vals[k + 1:k + 1 + n]]); k += 1 + n
        uvs = None
        um = re.search(r'MeshTextureCoords[ \t]*\w*[ \t\r\n]*\{', body)
        if um:
            u = [float(x) for x in NUM.findall(_block(body, um.end()))]
            n = int(u[0]); uvs = [tuple(u[1 + j * 2:3 + j * 2]) for j in range(n)]
        meshes.append({'name': m.group(1), 'vertices': verts, 'faces': faces, 'uvs': uvs})
    return meshes


if __name__ == '__main__':
    import sys
    for f in sys.argv[1:]:
        for m in read(f):
            v = m['vertices']
            xs, ys, zs = zip(*v)
            tris = sum(len(fc) - 2 for fc in m['faces'])
            print(f"{Path(f).name}: mesh '{m['name']}' {len(v)} vertices, {len(m['faces'])} faces ({tris} triangles), uvs {len(m['uvs']) if m['uvs'] else 0}, "
                  f"box x[{min(xs):.2f},{max(xs):.2f}] y[{min(ys):.2f},{max(ys):.2f}] z[{min(zs):.2f},{max(zs):.2f}]")
