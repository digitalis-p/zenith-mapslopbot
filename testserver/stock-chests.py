#!/usr/bin/env python3
"""Stock the base's chests on the local test server over RCON so a build can run.

The world download has NO container contents (every chest is empty), so material and supply
chests must be filled before the plugin can restock. Chest positions come from site files
(several may be given; their chests are merged):

  * the plugin's site config shape (testserver/site-config.moss.json, plugins/config/mapart.json or
    its "site" object): storage.mapChests/paneChests/xpChests/anvilChests/toolChests/foodChests/
    bootChests/outputChests/returnChests. It has no item->chest map, so material chests come from
  * a survey-style "chests.materials" {item: [{x,y,z,pair,stackYs|stack}...]} section
    (testserver/config/moss-materials.json, or the survey's site-config.draft.json, which also
    has chests.maps/panes/xp/tools/food/anvils/output/dump).

Usage: python3 -I stock-chests.py [options]
  --site FILE          site file, repeatable (default: $TESTSERVER_SITE_CONFIG, else
                       testserver/site-config.moss.json + testserver/config/moss-materials.json)
  --platform P[,P]     only material chests of these platforms: MOSS, SOUL_SAND, COLOR_SUPPRESSION (default all)
  --materials A[,B]    only these item ids (default: every material in the site file)
  --depth front|all    front: the double chest the bot opens (default); all: every chest of the sorter column
  --stacks N           stacks per chest half for materials (default 27 = full)
  --no-materials       skip material chests        --no-supplies   skip supply chests
  --clear              EMPTY every chest selected above (instead of filling)
  --clear-output       also empty output and return/dump chests
  --kit PLAYER         give an online PLAYER a starter kit (enchanted tools, food, Soul Speed boots)
  --dry-run            print the RCON commands instead of running them

Chunks holding the chests are force-loaded for the run and released afterwards (chunks that were
already force-loaded stay so). Exit status 1 if any chest could not be written.
"""
import importlib.util
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

_spec = importlib.util.spec_from_file_location("ts_rcon", os.path.join(HERE, "rcon.py"))
ts_rcon = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ts_rcon)

# Stack sizes that are not 64. The server accepts over-stacked NBT silently (64 pickaxes in one
# slot), which real 2b2t never produces, so every stack written here uses the real limit.
STACK_16 = ("minecraft:ender_pearl", "minecraft:snowball", "minecraft:egg", "minecraft:honey_bottle",
            "minecraft:bucket", "minecraft:armor_stand")
STACK_16_SUFFIX = ("_sign", "_banner")
STACK_1_SUFFIX = ("_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_boots", "_helmet", "_chestplate",
                  "_leggings", "shears", "_bucket", "elytra", "totem_of_undying", "shulker_box")


def max_stack(item):
    if item in STACK_16 or item.endswith(STACK_16_SUFFIX):
        return 16
    if item.endswith(STACK_1_SUFFIX):
        return 1
    return 64


TOOL_ENCH = {"minecraft:efficiency": 5, "minecraft:unbreaking": 3, "minecraft:mending": 1}
BOOT_ENCH = {"minecraft:soul_speed": 3, "minecraft:mending": 1, "minecraft:unbreaking": 3,
             "minecraft:protection": 4}

# role -> list of (item id, enchantments or None). Several items share a chest's slots evenly.
SUPPLIES = {
    "maps": [("minecraft:map", None)],
    "panes": [("minecraft:glass_pane", None)],
    "xp": [("minecraft:experience_bottle", None)],
    "anvils": [("minecraft:anvil", None)],
    "tools": [("minecraft:diamond_pickaxe", TOOL_ENCH), ("minecraft:diamond_axe", TOOL_ENCH),
              ("minecraft:diamond_shovel", TOOL_ENCH)],
    "food": [("minecraft:golden_carrot", None)],
    "boots": [("minecraft:netherite_boots", BOOT_ENCH)],
}
# survey-draft key / plugin-config key -> role
DRAFT_ROLES = {"maps": "maps", "panes": "panes", "xp": "xp", "anvils": "anvils", "tools": "tools",
               "food": "food", "boots": "boots", "output": "output", "dump": "return"}
CONFIG_ROLES = {"mapChests": "maps", "paneChests": "panes", "xpChests": "xp", "anvilChests": "anvils",
                "toolChests": "tools", "foodChests": "food", "bootChests": "boots",
                "outputChests": "output", "returnChests": "return"}

CLOCKWISE = {"north": "east", "east": "south", "south": "west", "west": "north"}
COUNTER_CLOCKWISE = {v: k for k, v in CLOCKWISE.items()}
STEP = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def die(msg):
    print("stock-chests: " + msg, file=sys.stderr)
    sys.exit(1)


def pos_of(d):
    return (int(d["x"]), int(d["y"]), int(d["z"]))


def fmt(p):
    return "%d %d %d" % p


class Server:
    def __init__(self, dry):
        self.dry = dry
        self.r = None
        self.sent = 0
        if not dry:
            self.connect()

    def connect(self):
        props = ts_rcon.read_props(os.path.join(HERE, "work", "server.properties"))
        with open(os.path.join(HERE, "work", ".rcon-password"), encoding="utf-8") as f:
            pw = f.read().strip()
        try:
            port = int(props.get("rcon.port") or os.environ.get("TESTSERVER_RCON_PORT") or "25576")
            self.r = ts_rcon.Rcon(props.get("server-ip", "127.0.0.1"), port, pw)
        except OSError as e:
            die("cannot reach RCON (is the server running? ./start.sh): %s" % e)

    def cmd(self, c):
        self.sent += 1
        if self.dry:
            print(c)
            return ""
        try:
            return self.r.command(c)
        except (ConnectionError, OSError):
            # One reconnect + retry; every command this script sends is idempotent except 'give'.
            self.r.close()
            self.connect()
            return self.r.command(c)

    def test(self, c):
        """Run an 'execute if ...' test; True when it passed. Dry-run: always True."""
        if self.dry:
            return True
        return self.cmd(c).startswith("Test passed")


def load_site(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if "site" in data and isinstance(data["site"], dict):
        data = data["site"]
    return data


def collect(site, args):
    """-> (materials: list of (item, [anchor dicts]), roles: dict role -> [pos dicts])"""
    mats = []
    chests = site.get("chests")
    if isinstance(chests, dict) and isinstance(chests.get("materials"), dict):
        for item, slots in sorted(chests["materials"].items()):
            if args.materials and item not in args.materials:
                continue
            sel = [s for s in slots if not args.platforms or str(s.get("platform", "")).upper() in args.platforms]
            if sel:
                mats.append((item, sel))
    roles = {}
    if isinstance(chests, dict):
        for key, role in DRAFT_ROLES.items():
            for e in chests.get(key) or []:
                if isinstance(e, dict) and "x" in e:
                    roles.setdefault(role, []).append(e)
    storage = site.get("storage")
    if isinstance(storage, dict):
        for key, role in CONFIG_ROLES.items():
            for e in storage.get(key) or []:
                if isinstance(e, dict) and "x" in e:
                    roles.setdefault(role, []).append(e)
    return mats, roles


def chest_halves(srv, p, cache):
    """Return the block positions of the chest at p (1 or 2 halves), [] if p is not a container."""
    if p in cache:
        return cache[p]
    loc = fmt(p)
    res = []
    kind = None
    for k in ("minecraft:chest", "minecraft:trapped_chest"):
        if srv.test("execute if block %s %s" % (loc, k)):
            kind = k
            break
    if kind:
        if srv.dry or srv.test("execute if block %s %s[type=single]" % (loc, kind)):
            res = [p]
        else:
            # Vanilla ChestBlock.getConnectedDirection: LEFT -> facing clockwise, RIGHT -> counter-clockwise.
            left = srv.test("execute if block %s %s[type=left]" % (loc, kind))
            facing = "west"
            for f in ("north", "east", "south"):
                if srv.test("execute if block %s %s[facing=%s]" % (loc, kind, f)):
                    facing = f
                    break
            d = CLOCKWISE[facing] if left else COUNTER_CLOCKWISE[facing]
            dx, dz = STEP[d]
            res = [p, (p[0] + dx, p[1], p[2] + dz)]
    elif srv.test("execute if block %s minecraft:barrel" % loc):
        res = [p]
    cache[p] = res
    for q in res:
        cache[q] = res
    return res


def anchors_to_blocks(srv, anchor, depth, cache):
    """Turn one material slot entry into the container blocks to fill."""
    out = []
    base = pos_of(anchor)
    pair = pos_of(anchor["pair"]) if isinstance(anchor.get("pair"), dict) else None
    if pair:
        out += [base, pair]
    else:
        out += chest_halves(srv, base, cache)
    if depth == "all":
        extra = []
        for s in anchor.get("stack") or []:
            extra.append(pos_of(s))
        for y in anchor.get("stackYs") or []:
            if y == base[1]:
                continue
            extra.append((base[0], y, base[2]))
            if pair:
                extra.append((pair[0], y, pair[2]))
                # some columns shift the top double chest one block (e.g. z+1/z-1)
                extra.append((pair[0], y, pair[2] - (base[2] - pair[2])))
        for q in extra:
            for h in chest_halves(srv, q, cache):
                out.append(h)
    seen = []
    for q in out:
        if q not in seen:
            seen.append(q)
    return seen


def short(rid):
    return rid[len("minecraft:"):] if rid.startswith("minecraft:") else rid


def item_snbt(slot, item, count, ench):
    s = '{Slot:%db,id:"%s",count:%d' % (slot, short(item), count)
    if ench:
        s += ',components:{enchantments:{%s}}' % ",".join('"%s":%d' % (short(k), v) for k, v in ench.items())
    return s + "}"


def fill_cmds(block, items, stacks):
    """Commands that set the container at `block` to exactly these stacks (empty list = clear).
    items: list of (item, ench); slots are split evenly among them. Vanilla RCON drops requests
    over 1460 bytes, so the list is written with one 'data merge' plus 'append's as needed."""
    entries = []
    if items:
        n = min(stacks, 27)
        for slot in range(n):
            item, ench = items[slot * len(items) // n]
            entries.append(item_snbt(slot, item, max_stack(item), ench))
    loc = fmt(block)
    head = "data merge block %s {Items:[%%s]}" % loc
    first = []
    while entries and len(head % ",".join(first + entries[:1])) <= ts_rcon.MAX_COMMAND:
        first.append(entries.pop(0))
    cmds = [head % ",".join(first)]
    cmds += ["data modify block %s Items append value %s" % (loc, e) for e in entries]
    return cmds


def chunk_of(p):
    return (p[0] >> 4, p[2] >> 4)


def parse_forced(text):
    return set((int(a), int(b)) for a, b in re.findall(r"\[(-?\d+), (-?\d+)\]", text))


def main(argv):
    import argparse
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--site", action="append", default=[])
    ap.add_argument("--platform", default="")
    ap.add_argument("--materials", default="")
    ap.add_argument("--depth", choices=("front", "all"), default="front")
    ap.add_argument("--stacks", type=int, default=27)
    ap.add_argument("--no-materials", action="store_true")
    ap.add_argument("--no-supplies", action="store_true")
    ap.add_argument("--clear", action="store_true")
    ap.add_argument("--clear-output", action="store_true")
    ap.add_argument("--kit")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args(argv[1:])
    if args.help:
        print(__doc__)
        return 0
    args.platforms = set(p.strip().upper() for p in args.platform.split(",") if p.strip() and p.strip().lower() != "all")
    args.materials = set(m.strip() if ":" in m else "minecraft:" + m.strip() for m in args.materials.split(",") if m.strip())

    site_paths = list(args.site)
    if not site_paths and os.environ.get("TESTSERVER_SITE_CONFIG"):
        site_paths = os.environ["TESTSERVER_SITE_CONFIG"].split(os.pathsep)
    if not site_paths and not args.kit:
        site_paths = [os.path.join(HERE, "local", "site-config.moss.json"), os.path.join(HERE, "local", "moss-materials.json")]
    for sp in site_paths:
        if not os.path.exists(sp):
            die("site file not found: %s" % sp)

    srv = Server(args.dry_run)
    failures = 0
    cache = {}
    plan = []  # (role/item label, block, items)

    if site_paths:
        mats, roles = [], {}
        for sp in site_paths:
            m, r = collect(load_site(sp), args)
            mats += m
            for k, v in r.items():
                roles.setdefault(k, []).extend(v)
        print("site files: %s (%d materials selected, roles: %s)" % (
            ", ".join(os.path.relpath(sp) for sp in site_paths), len(mats),
            ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(roles.items())) or "none"))

        anchors = []
        if not args.no_materials:
            anchors += [pos_of(a) for _, slots in mats for a in slots]
        if not args.no_supplies or args.clear_output:
            anchors += [pos_of(e) for es in roles.values() for e in es]
        chunks = set()
        for p in anchors:
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    chunks.add(chunk_of((p[0] + dx, p[1], p[2] + dz)))
        already = parse_forced(srv.cmd("forceload query")) if not args.dry_run else set()
        added = sorted(chunks - already)
        for cx, cz in added:
            srv.cmd("forceload add %d %d" % (cx * 16, cz * 16))
        if added:
            print("force-loading %d chunks for the run" % len(added))
        try:
            # Wait until every anchor's chunk is loaded.
            deadline = time.time() + 60
            pending = list(chunks)
            while pending and not args.dry_run:
                pending = [c for c in pending if not srv.test("execute if loaded %d 0 %d" % (c[0] * 16, c[1] * 16))]
                if pending:
                    if time.time() > deadline:
                        die("chunks did not load: %s" % pending[:5])
                    time.sleep(0.5)

            if not args.no_materials:
                for item, slots in mats:
                    for a in slots:
                        for b in anchors_to_blocks(srv, a, args.depth, cache):
                            plan.append((item, b, [] if args.clear else [(item, None)]))
            role_items = {}
            if not args.no_supplies:
                for role, es in roles.items():
                    if role in ("output", "return"):
                        continue
                    for e in es:
                        p = pos_of(e)
                        blocks = ([p, pos_of(e["pair"])] if isinstance(e.get("pair"), dict)
                                  else chest_halves(srv, p, cache))
                        for b in blocks:
                            role_items.setdefault(b, {"roles": [], "items": []})
                            if role not in role_items[b]["roles"]:
                                role_items[b]["roles"].append(role)
                                role_items[b]["items"] += SUPPLIES.get(role, [])
            if args.clear_output:
                for role in ("output", "return"):
                    for e in roles.get(role, []):
                        p = pos_of(e)
                        blocks = ([p, pos_of(e["pair"])] if isinstance(e.get("pair"), dict)
                                  else chest_halves(srv, p, cache))
                        for b in blocks:
                            role_items[b] = {"roles": [role], "items": []}
            for b, ri in role_items.items():
                plan.append(("+".join(ri["roles"]), b, [] if args.clear else ri["items"]))

            done = {}
            for label, b, items in plan:
                stacks = args.stacks if items and label.startswith("minecraft:") else 27
                ok = True
                for c in fill_cmds(b, items, stacks):
                    reply = srv.cmd(c)
                    if not (args.dry_run or reply.startswith("Modified block data") or reply.startswith("Nothing changed")):
                        ok = False
                        print("  FAILED %s at %s: %s" % (label, fmt(b), reply.strip()[:160]))
                        break
                if ok:
                    done[label] = done.get(label, 0) + 1
                else:
                    failures += 1
            verb = "emptied" if args.clear else "filled"
            print("%s %d chest blocks (%d labels); %d failures" % (verb, sum(done.values()), len(done), failures))
            for label in sorted(done):
                if not label.startswith("minecraft:"):
                    print("  %-28s %d chest blocks" % (label, done[label]))
        finally:
            for cx, cz in added:
                srv.cmd("forceload remove %d %d" % (cx * 16, cz * 16))
            if added:
                print("released %d force-loaded chunks" % len(added))

    if args.kit:
        name = args.kit
        if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name):
            die("invalid player name: %s" % name)
        kit = [(i, e, 1) for i, e in SUPPLIES["tools"]] + [("minecraft:netherite_boots", BOOT_ENCH, 1),
                                                          ("minecraft:golden_carrot", None, 64)]
        for item, ench, count in kit:
            comp = "[enchantments={%s}]" % ",".join("%s:%d" % (k.split(":")[1], v) for k, v in ench.items()) if ench else ""
            reply = srv.cmd("give %s %s%s %d" % (name, item, comp, count))
            print("  kit: %s" % (reply.strip() or "(no output)"))
            if not args.dry_run and not reply.startswith("Gave"):
                failures += 1

    print("rcon commands: %d" % srv.sent)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
