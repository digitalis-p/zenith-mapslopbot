#!/usr/bin/env python3
"""Minimal NBT tool (python3 stdlib only; run with -I).

Usage:
  python3 -I nbt.py FILE [PATH...]
      Print FILE (gzip / zlib / uncompressed NBT: level.dat, playerdata/*.dat) as JSON, or only the
      dotted PATHs given, e.g. Data.DataVersion Data.Version.Name Pos.0
  python3 -I nbt.py seed-player WORLD_DIR NAME X Y Z
      Write WORLD_DIR/playerdata/<offline uuid of NAME>.dat so that the offline player NAME joins
      standing on block X Y Z (feet at Y) with a forced respawn point there. Vanilla otherwise
      puts a new player on the highest block above the world spawn.
"""
import gzip
import hashlib
import json
import os
import struct
import sys
import uuid
import zlib


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        b = self.d[self.i:self.i + n]
        if len(b) != n:
            raise EOFError("truncated NBT")
        self.i += n
        return b

    def u(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(">" + fmt, self.take(size))[0]

    def string(self):
        n = self.u("H")
        return self.take(n).decode("utf-8", errors="replace")

    def payload(self, t):
        if t == 1:
            return self.u("b")
        if t == 2:
            return self.u("h")
        if t == 3:
            return self.u("i")
        if t == 4:
            return self.u("q")
        if t == 5:
            return self.u("f")
        if t == 6:
            return self.u("d")
        if t == 7:
            n = self.u("i")
            return list(self.take(n))
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u("b")
            n = self.u("i")
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                ct = self.u("b")
                if ct == 0:
                    return out
                name = self.string()
                out[name] = self.payload(ct)
        if t == 11:
            n = self.u("i")
            return [self.u("i") for _ in range(n)]
        if t == 12:
            n = self.u("i")
            return [self.u("q") for _ in range(n)]
        raise ValueError("bad tag type %d at %d" % (t, self.i))

    def root(self):
        t = self.u("b")
        if t != 10:
            raise ValueError("root is not a compound")
        self.string()
        return self.payload(10)


def load_bytes(raw):
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    elif raw[:1] == b"\x78":
        raw = zlib.decompress(raw)
    return Reader(raw).root()


def load(path):
    with open(path, "rb") as f:
        return load_bytes(f.read())


def get(tree, dotted):
    cur = tree
    for part in dotted.split("."):
        if isinstance(cur, list):
            cur = cur[int(part)]
        else:
            cur = cur[part]
    return cur


class Tag:
    """Typed value for the writer: Tag(type_id, value). Lists: value = (element_type, [values])."""

    def __init__(self, t, v):
        self.t = t
        self.v = v


def _enc_str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _enc(tag):
    t, v = tag.t, tag.v
    if t == 1:
        return struct.pack(">b", v)
    if t == 3:
        return struct.pack(">i", v)
    if t == 4:
        return struct.pack(">q", v)
    if t == 5:
        return struct.pack(">f", v)
    if t == 6:
        return struct.pack(">d", v)
    if t == 8:
        return _enc_str(v)
    if t == 9:
        et, items = v
        return struct.pack(">bi", et if items else 0, len(items)) + b"".join(_enc(Tag(et, i)) for i in items)
    if t == 10:
        out = b""
        for k, child in v.items():
            out += struct.pack(">b", child.t) + _enc_str(k) + _enc(child)
        return out + b"\x00"
    if t == 11:
        return struct.pack(">i", len(v)) + b"".join(struct.pack(">i", i) for i in v)
    raise ValueError("unsupported tag type %d" % t)


def dump_bytes(root):
    """Gzip-compressed NBT file bytes for a root compound Tag."""
    return gzip.compress(struct.pack(">b", 10) + _enc_str("") + _enc(root))


def offline_uuid(name):
    """Java UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))."""
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(h))


def seed_player(world_dir, name, x, y, z):
    level = load(os.path.join(world_dir, "level.dat"))
    dv = int(level["Data"]["DataVersion"])
    u = offline_uuid(name)
    ints = list(struct.unpack(">iiii", u.bytes))
    dim = Tag(8, "minecraft:overworld")
    root = Tag(10, {
        "DataVersion": Tag(3, dv),
        "UUID": Tag(11, ints),
        "Pos": Tag(9, (6, [x + 0.5, float(y), z + 0.5])),
        "Rotation": Tag(9, (5, [0.0, 0.0])),
        "Motion": Tag(9, (6, [0.0, 0.0, 0.0])),
        "OnGround": Tag(1, 1),
        "Dimension": dim,
        "playerGameType": Tag(3, 0),
        "Health": Tag(5, 20.0),
        "foodLevel": Tag(3, 20),
        "respawn": Tag(10, {
            "pos": Tag(11, [x, y, z]),
            "forced": Tag(1, 1),
            "yaw": Tag(5, 0.0),
            "pitch": Tag(5, 0.0),
            "dimension": dim,
        }),
    })
    pdir = os.path.join(world_dir, "playerdata")
    os.makedirs(pdir, exist_ok=True)
    path = os.path.join(pdir, "%s.dat" % u)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(dump_bytes(root))
    os.replace(tmp, path)
    check = load(path)
    assert check["Pos"][0] == x + 0.5 and check["respawn"]["pos"] == [x, y, z]
    print("seeded %s (%s) at %d %d %d" % (name, u, x, y, z))


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    if argv[1] == "seed-player":
        if len(argv) != 7:
            print(__doc__, file=sys.stderr)
            return 2
        seed_player(argv[2], argv[3], int(argv[4]), int(argv[5]), int(argv[6]))
        return 0
    tree = load(argv[1])
    if len(argv) == 2:
        print(json.dumps(tree, indent=1, default=str))
        return 0
    for p in argv[2:]:
        try:
            print("%s=%s" % (p, json.dumps(get(tree, p), default=str)))
        except (KeyError, IndexError, ValueError):
            print("%s=<missing>" % p)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
