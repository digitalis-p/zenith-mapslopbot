"""Analyse a PNG-to-NBT CrubTech export: python3 -I analyze.py NBTPY_DIR FILE"""
import sys
from collections import Counter, defaultdict

sys.path.insert(0, sys.argv[1])
import nbt  # noqa: E402

root = nbt.load(sys.argv[2])
if isinstance(root, tuple):
    root = root[1]
print("keys", sorted(root.keys()))
size = root["size"]
print("size", size, "DataVersion", root.get("DataVersion"), "author", root.get("author"))
pal = root.get("palette")
names = []
for p in pal:
    n = p.get("Name") or p.get("id")
    props = p.get("Properties") or p.get("properties") or {}
    names.append(n + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else ""))
print("palette", len(names))
per_y = defaultdict(Counter)
cols = defaultdict(list)
for b in root["blocks"]:
    x, y, z = b["pos"]
    n = names[b["state"]]
    per_y[y][n] += 1
    cols[(x, z)].append((y, n))
for y in sorted(per_y):
    c = per_y[y]
    print(f"y{y}: {sum(c.values())} blocks:", ", ".join(f"{k.replace('minecraft:','')} {v}" for k, v in c.most_common(8)))
# glass level
G = None
for y in sorted(per_y):
    if per_y[y].get("minecraft:glass", 0) >= 128 * 64:
        G = y
print("glass level", G)
# per column, per layer: number of blocks
twolow = twoup = 0
nooblow = Counter()
noobup = Counter()
lowmoss = 0
dist = Counter()
for (x, z), lst in cols.items():
    low = [(y, n) for y, n in lst if y < G]
    up = [(y, n) for y, n in lst if y > G]
    if len(low) > 1:
        twolow += 1
    if len(up) > 1:
        twoup += 1
    if z == 0:
        for y, n in low:
            nooblow[(y, n)] += 1
        for y, n in up:
            noobup[(y, n)] += 1
    for y, n in low:
        if "moss" in n:
            lowmoss += 1
    dist[(len(low), len(up))] += 1
print("columns with 2 blocks on lower layer", twolow, "upper", twoup, "lower moss", lowmoss)
print("noobline lower", dict(nooblow), "upper", dict(noobup))
print("(lower,upper) per column", dict(dist))
# glass coverage at G: z range
gz = sorted({z for (x, z), lst in cols.items() for y, n in lst if y == G and n == "minecraft:glass"})
print("glass z range", gz[0] if gz else None, gz[-1] if gz else None, "count", sum(1 for (x, z), lst in cols.items() for y, n in lst if y == G))
# parity check: dominant pixel = (x + (z-1)) odd
par = Counter()
for (x, z), lst in cols.items():
    if z == 0:
        continue
    row = z - 1
    dom = (x + row) & 1
    for y, n in lst:
        if n in ("minecraft:glass",):
            continue
        layer = "low" if y < G else "up"
        role = "filler" if n in ("minecraft:resin_block", "minecraft:moss_block") else "colour"
        par[(layer, y - G, "dom" if dom else "rec", role)] += 1
for k in sorted(par):
    print(k, par[k])
