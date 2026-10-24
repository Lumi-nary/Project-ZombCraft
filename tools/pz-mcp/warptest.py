"""Measures how well Steve's blocks stay glued to PZ's world while the camera turns.

Finds the right edge of the left wooden block in screenshots, calibrates how that edge moves with yaw while standing
still, then spins the camera and compares the edge's position with where it should be for the yaw of each screenshot
(the control API reports the yaw at the frame in the X-Look header). Run once with warp on and once with warp off.

  python warptest.py            # on and off, prints mean/max error in pixels
"""
import json, struct, sys, time, urllib.request, zlib
import numpy as np
import pzcore as pz


def decode_png(data: bytes) -> np.ndarray:
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, w = 8, b"", 0
    ct = 0
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if typ == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", body)
            assert bd == 8 and il == 0
        elif typ == b"IDAT":
            idat += body
        pos += 12 + n
    ch = {2: 3, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = w * ch
    out = np.zeros((h, stride), np.uint8)
    prev = np.zeros(stride, np.uint8)
    p = 0
    for y in range(h):
        f = raw[p]; line = np.frombuffer(raw, np.uint8, stride, p + 1).astype(np.int32); p += 1 + stride
        if f == 0: cur = line
        elif f == 1:
            cur = line.copy()
            for i in range(ch, stride): cur[i] = (cur[i] + cur[i - ch]) & 255
        elif f == 2: cur = (line + prev) & 255
        elif f == 3:
            cur = line.copy()
            for i in range(stride):
                a = cur[i - ch] if i >= ch else 0
                cur[i] = (cur[i] + ((a + int(prev[i])) >> 1)) & 255
        elif f == 4:
            cur = line.copy()
            for i in range(stride):
                a = cur[i - ch] if i >= ch else 0
                b = int(prev[i]); c = int(prev[i - ch]) if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 255
        out[y] = cur.astype(np.uint8); prev = out[y]
    return out.reshape(h, w, ch)


def shot():
    req = urllib.request.Request(f"http://127.0.0.1:{pz.PORT}/screenshot?scale=0.5", headers={"X-Token": pz._token()})
    with urllib.request.urlopen(req, timeout=20) as r:
        look = [float(v) for v in r.headers["X-Look"].split(",")]
        return decode_png(r.read()), look


def wood_edge(img: np.ndarray) -> float:
    """Right edge (px) of the leftmost wooden block, from rows through its side face. Wood has a fixed colour ratio
    (G/R ~ 0.8, B/R ~ 0.45 whether lit or in shade); gravel, plants and sky do not."""
    h, w, _ = img.shape
    edges = []
    for fy in (0.60, 0.64, 0.68):
        row = img[int(h * fy)].astype(float)
        r = np.maximum(row[:, 0], 1)
        wood = (row[:, 0] > 40) & (np.abs(row[:, 1] / r - 0.80) < 0.07) & (np.abs(row[:, 2] / r - 0.46) < 0.09)
        runs, start, last = [], None, None
        for x in range(int(w * 0.8)):
            if wood[x]:
                if start is None:
                    start = x
                last = x
            elif start is not None and x - last > 6:
                runs.append((start, last)); start = None
        if start is not None:
            runs.append((start, last))
        runs = [r_ for r_ in runs if r_[1] - r_[0] >= 25]
        if runs:
            edges.append(runs[0][1])
    return float(np.median(edges)) if edges else float("nan")


def main():
    pz.cmd("warp on")
    pz.send_input(release=True)
    pz.lua("UIManager.getSpeedControls():SetCurrentGameSpeed(1) return 1")
    cal = []
    for yaw in np.arange(97, 82.9, -1.0):
        pz.cmd(f"look {yaw} 5")
        time.sleep(0.8)
        img, look = shot()
        e = wood_edge(img)
        if e == e:
            cal.append((look[0], e))
    cal.sort()
    ys = np.array([c[0] for c in cal]); xs = np.array([c[1] for c in cal])
    print("calibration (yaw -> edge px):", [(round(a, 1), round(b)) for a, b in cal])
    rate = float(sys.argv[1]) if len(sys.argv) > 1 else -60.0
    for mode in ("on", "off"):
        pz.cmd(f"warp {mode}")
        errs, warps = [], []
        for _ in range(16):
            pz.cmd("look 96 5"); time.sleep(0.9)
            pz.cmd(f"spin {rate} 700")
            time.sleep(0.05)
            img, look = shot()
            e = wood_edge(img)
            if e == e and ys.min() + 1 <= look[0] <= ys.max() - 1:
                errs.append(e - float(np.interp(look[0], ys, xs)))
                warps.append(look[2])
        print(f"warp {mode}: n={len(errs)} edge error px (measured - expected for the frame's yaw): "
              + ", ".join(f"{v:+.0f}" for v in errs) + (f"  | mean {np.mean(errs):+.1f}  mean|err| {np.mean(np.abs(errs)):.1f}" if errs else "")
              + (f"  | mean warp applied {np.mean(warps):+.2f} deg" if warps else ""))
    pz.cmd("warp on")
    pz.cmd("look 90 5")


if __name__ == "__main__":
    main()
