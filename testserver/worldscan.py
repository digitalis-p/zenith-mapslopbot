#!/usr/bin/env python3
"""Read blocks straight from the test world's region files (python3 stdlib only; run with -I).

Run `rcon.py "save-all flush"` first: only what the server has written to disk is visible.

Usage:
  python3 -I worldscan.py count X0 Y0 Z0 X1 Y1 Z1        histogram of block ids in the box
  python3 -I worldscan.py list  X0 Y0 Z0 X1 Y1 Z1        every non-air block with its full state
  python3 -I worldscan.py snapshot FILE X0 Y0 Z0 X1 Y1 Z1  save the box (full states) as JSON
  python3 -I worldscan.py diff FILE                      compare the world with a snapshot
Options: --world DIR (default testserver/work/world)
"""
import json
import os
import struct
import sys
import zlib
import gzip

HERE = os.path.dirname(os.path.abspath(__file__))
sys_path_nbt = os.path.join(HERE, "nbt.py")

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("ts_nbt", sys_path_nbt)
ts_nbt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ts_nbt)


class World:
    def __init__(self, path):
        self.path = path
        self.regions = {}
        self.chunks = {}

    def _region(self, rx, rz):
        key = (rx, rz)
        if key not in self.regions:
            p = os.path.join(self.path, "region", "r.%d.%d.mca" % (rx, rz))
            self.regions[key] = open(p, "rb").read() if os.path.exists(p) else b""
        return self.regions[key]

    def chunk(self, cx, cz):
        key = (cx, cz)
        if key in self.chunks:
            return self.chunks[key]
        data = self._region(cx >> 5, cz >> 5)
        sections = {}
        if len(data) >= 8192:
            i = (cx & 31) + (cz & 31) * 32
            loc = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
            off, cnt = loc >> 8, loc & 0xFF
            if off:
                p = off * 4096
                length = struct.unpack(">I", data[p:p + 4])[0]
                ctype = data[p + 4]
                payload = data[p + 5:p + 4 + length]
                if ctype == 1:
                    raw = gzip.decompress(payload)
                elif ctype == 2:
                    raw = zlib.decompress(payload)
                elif ctype == 3:
                    raw = payload
                else:
                    raise RuntimeError("unsupported chunk compression %d" % ctype)
                root = ts_nbt.Reader(raw).root()
                for sec in root.get("sections", []):
                    bs = sec.get("block_states")
                    if bs is None:
                        continue
                    sections[sec["Y"]] = self._decode(bs)
        self.chunks[key] = sections
        return sections

    @staticmethod
    def _state(entry):
        props = entry.get("Properties")
        if props:
            return "%s[%s]" % (entry["Name"], ",".join("%s=%s" % kv for kv in sorted(props.items())))
        return entry["Name"]

    def _decode(self, bs):
        pal = [self._state(e) for e in bs["palette"]]
        if "data" not in bs or len(pal) == 1:
            return pal, None
        bits = max(4, (len(pal) - 1).bit_length())
        per = 64 // bits
        mask = (1 << bits) - 1
        idx = []
        for v in bs["data"]:
            v &= 0xFFFFFFFFFFFFFFFF
            for _ in range(per):
                idx.append(v & mask)
                v >>= bits
        return pal, idx[:4096]

    def block(self, x, y, z):
        secs = self.chunk(x >> 4, z >> 4)
        s = secs.get(y >> 4)
        if s is None:
            return "minecraft:air"
        pal, idx = s
        if idx is None:
            return pal[0]
        return pal[idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]]


def box(args):
    x0, y0, z0, x1, y1, z1 = [int(a) for a in args]
    for y in range(min(y0, y1), max(y0, y1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                yield x, y, z


def main(argv):
    args = argv[1:]
    world_dir = os.path.join(HERE, "work", "world")
    if "--world" in args:
        i = args.index("--world")
        world_dir = args[i + 1]
        del args[i:i + 2]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0 if args else 2
    w = World(world_dir)
    cmd = args[0]
    if cmd == "count" and len(args) == 7:
        hist = {}
        for x, y, z in box(args[1:]):
            b = w.block(x, y, z).split("[")[0]
            hist[b] = hist.get(b, 0) + 1
        for b, n in sorted(hist.items(), key=lambda kv: -kv[1]):
            print("%7d %s" % (n, b))
        return 0
    if cmd == "list" and len(args) == 7:
        for x, y, z in box(args[1:]):
            b = w.block(x, y, z)
            if b not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
                print("%d %d %d %s" % (x, y, z, b))
        return 0
    if cmd == "snapshot" and len(args) == 8:
        snap = {"box": [int(a) for a in args[2:]], "blocks": {}}
        for x, y, z in box(args[2:]):
            snap["blocks"]["%d,%d,%d" % (x, y, z)] = w.block(x, y, z)
        with open(args[1], "w", encoding="utf-8") as f:
            json.dump(snap, f)
        print("saved %d blocks to %s" % (len(snap["blocks"]), args[1]))
        return 0
    if cmd == "diff" and len(args) == 2:
        with open(args[1], encoding="utf-8") as f:
            snap = json.load(f)
        changed = 0
        for key, old in snap["blocks"].items():
            x, y, z = [int(v) for v in key.split(",")]
            new = w.block(x, y, z)
            if new != old:
                changed += 1
                if changed <= 200:
                    print("%s: %s -> %s" % (key, old, new))
        print("%d of %d blocks differ" % (changed, len(snap["blocks"])))
        return 1 if changed else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
