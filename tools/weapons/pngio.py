"""Minimal PNG reading/writing with numpy and zlib only (Pillow is not installed on this machine)."""
import struct
import zlib

import numpy as np


def read(path):
    """8-bit non-interlaced PNG (grey, grey+alpha, RGB, RGBA or palette) as an HxWx4 uint8 array."""
    return read_bytes(open(path, 'rb').read(), path)


def read_bytes(data, path='<bytes>'):
    assert data[:8] == b'\x89PNG\r\n\x1a\n', path
    pos, idat, palette, trns = 8, [], None, None
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
            assert depth == 8 and interlace == 0, (path, depth, interlace)
        elif kind == b'PLTE':
            palette = np.frombuffer(body, np.uint8).reshape(-1, 3)
        elif kind == b'tRNS':
            trns = np.frombuffer(body, np.uint8)
        elif kind == b'IDAT':
            idat.append(body)
        elif kind == b'IEND':
            break
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = np.frombuffer(zlib.decompress(b''.join(idat)), np.uint8).reshape(h, 1 + w * channels)
    out = np.zeros((h, w * channels), np.uint8)
    prev = np.zeros(w * channels, np.int32)
    for y in range(h):
        f, line = int(raw[y, 0]), raw[y, 1:].astype(np.int32)
        if f == 0:
            cur = line
        elif f == 2:
            cur = (line + prev) & 255
        else:
            cur = np.zeros_like(line)
            for x in range(len(line)):
                a = cur[x - channels] if x >= channels else 0
                b = prev[x]
                c = prev[x - channels] if x >= channels else 0
                if f == 1:
                    p = a
                elif f == 3:
                    p = (a + b) >> 1
                else:
                    pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                    p = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                cur[x] = (line[x] + p) & 255
        out[y] = cur
        prev = cur
    px = out.reshape(h, w, channels)
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., 3] = 255
    if ctype == 6:
        rgba[:] = px
    elif ctype == 2:
        rgba[..., :3] = px
    elif ctype == 0:
        rgba[..., :3] = px
    elif ctype == 4:
        rgba[..., :3] = px[..., :1]
        rgba[..., 3] = px[..., 1]
    else:
        rgba[..., :3] = palette[px[..., 0]]
        if trns is not None:
            alpha = np.full(256, 255, np.uint8)
            alpha[:len(trns)] = trns
            rgba[..., 3] = alpha[px[..., 0]]
    return rgba


def write(path, array):
    """HxWx3 or HxWx4 uint8 array to a PNG."""
    with open(path, 'wb') as f:
        f.write(to_bytes(array))


def to_bytes(array):
    array = np.ascontiguousarray(array, np.uint8)
    h, w, c = array.shape
    raw = np.zeros((h, 1 + w * c), np.uint8)
    raw[:, 1:] = array.reshape(h, w * c)

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6 if c == 4 else 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw.tobytes(), 6)) + chunk(b'IEND', b''))


_FONT = {'0': '111101101101111', '1': '010110010010111', '2': '111001111100111', '3': '111001111001111', '4': '101101111001001',
         '5': '111100111001111', '6': '111100111101111', '7': '111001001001001', '8': '111101111101111', '9': '111101111001111',
         '.': '000000000000010', '-': '000000111000000', ' ': '000000000000000', ':': '000010000010000'}
_LETTERS = {'a': '010101111101101', 'b': '110101110101110', 'c': '011100100100011', 'd': '110101101101110', 'e': '111100110100111',
            'f': '111100110100100', 'g': '011100101101011', 'h': '101101111101101', 'i': '111010010010111', 'j': '001001001101010',
            'k': '101101110101101', 'l': '100100100100111', 'm': '101111111101101', 'n': '110101101101101', 'o': '010101101101010',
            'p': '110101110100100', 'q': '010101101011001', 'r': '110101110101101', 's': '011100010001110', 't': '111010010010010',
            'u': '101101101101111', 'v': '101101101101010', 'w': '101101111111101', 'x': '101101010101101', 'y': '101101010010010',
            'z': '111001010100111', '_': '000000000000111', '/': '001001010100100'}
_FONT.update(_LETTERS)


def text(array, x, y, string, color=(255, 255, 255), scale=2):
    """Draws lower-case text with a 3x5 font into an HxWx3 array (in place)."""
    for ch in string.lower():
        bits = _FONT.get(ch, _FONT[' '])
        for i, bit in enumerate(bits):
            if bit == '1':
                px, py = x + (i % 3) * scale, y + (i // 3) * scale
                array[py:py + scale, px:px + scale] = color
        x += 4 * scale
