#!/usr/bin/env python3
"""Measure a platform's piston clear on the local test server (python3 stdlib only; run with -I).

Steps: snapshot the flying-machine areas -> force-load the platform -> fill the art layer with a
test pattern -> press the clear actuator -> sample the art layer over time -> exact final count,
leftover items, and whether the machines are back in their parked state. A JSON report is written
to work/clear-exp/.

Usage: python3 -I clear-experiment.py [options]
  --site FILE         site config with platforms[] (default testserver/local/site-config.moss.json)
  --platform ID       platform id (default: first platform)
  --press X,Y,Z       actuator to press (repeatable, pressed in order; default: platform clear[].pos)
  --method rcon|bot   rcon (default): setblock the button powered=true, then false after --hold
                      ticks, like a real press; bot: ZenithProxy 'pathfinder click right X Y Z'
  --hold TICKS        rcon press length (default 20 = stone button)
  --fill BLOCK        test pattern for the art layer (default minecraft:white_carpet; 'none' = keep)
  --interval S        sampling period in seconds (default 5)
  --settle S          stop when nothing changed for S seconds (default 60)
  --timeout S         give up after S seconds (default 900)
  --cleanup           afterwards remove leftover pattern blocks and dropped items
"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def load_mod(name, fn):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fn))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


ts_rcon = load_mod("ts_rcon", "rcon.py")
ts_scan = load_mod("ts_scan", "worldscan.py")


class Srv:
    def __init__(self):
        props = ts_rcon.read_props(os.path.join(HERE, "work", "server.properties"))
        with open(os.path.join(HERE, "work", ".rcon-password"), encoding="utf-8") as f:
            pw = f.read().strip()
        port = int(props.get("rcon.port") or os.environ.get("TESTSERVER_RCON_PORT") or "25576")
        self.r = ts_rcon.Rcon(props.get("server-ip", "127.0.0.1"), port, pw)

    def __call__(self, c):
        return self.r.command(c)

    def passed(self, c):
        return self(c).startswith("Test passed")

    def count(self, c):
        m = re.search(r"count: (\d+)", self(c), re.IGNORECASE)
        return int(m.group(1)) if m else 0


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", default=os.path.join(HERE, "local", "site-config.moss.json"))
    ap.add_argument("--platform")
    ap.add_argument("--press", action="append", default=[])
    ap.add_argument("--method", choices=("rcon", "bot"), default="rcon")
    ap.add_argument("--hold", type=int, default=20)
    ap.add_argument("--fill", default="minecraft:white_carpet")
    ap.add_argument("--interval", type=float, default=5)
    ap.add_argument("--settle", type=float, default=60)
    ap.add_argument("--timeout", type=float, default=900)
    ap.add_argument("--cleanup", action="store_true")
    a = ap.parse_args(argv[1:])

    with open(a.site, encoding="utf-8") as f:
        site = json.load(f)
    site = site.get("site", site)
    plats = site["platforms"]
    plat = next((p for p in plats if p["id"] == a.platform), None) if a.platform else plats[0]
    if plat is None:
        raise SystemExit("no platform %r" % a.platform)
    x0, z0, y = plat["minX"], plat["minZ"], plat["artY"]
    x1, z1 = x0 + 127, z0 + 127
    presses = [tuple(int(v) for v in p.split(",")) for p in a.press] or \
        [(c["pos"]["x"], c["pos"]["y"], c["pos"]["z"]) for c in plat.get("clear", [])]
    if not presses:
        raise SystemExit("nothing to press")

    out_dir = os.path.join(HERE, "work", "clear-exp")
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    report = {"platform": plat["id"], "box": [x0, y, z0, x1, y, z1], "presses": presses,
              "method": a.method, "fill": a.fill, "samples": []}

    s = Srv()
    # Platform plus a margin for the parked machines and the turnaround station.
    fx0, fz0, fx1, fz1 = x0 - 16, z0 - 16, x1 + 16, z1 + 16
    s("forceload add %d %d %d %d" % (fx0, fz0, fx1, fz1))
    try:
        for cx in range(fx0 >> 4, (fx1 >> 4) + 1):
            for cz in range(fz0 >> 4, (fz1 >> 4) + 1):
                deadline = time.time() + 60
                while not s.passed("execute if loaded %d 0 %d" % (cx * 16, cz * 16)):
                    if time.time() > deadline:
                        raise SystemExit("chunk %d,%d did not load" % (cx, cz))
                    time.sleep(0.2)
        log("force-loaded platform area (%d..%d, %d..%d)" % (fx0, fx1, fz0, fz1))

        # Snapshot the machine areas (east + west strips beside the platform) from disk.
        s("save-all flush")
        world = ts_scan.World(os.path.join(HERE, "work", "world"))
        strips = {"east": (x1 + 1, y - 2, z0 - 8, x1 + 16, y + 6, z1 + 8),
                  "west": (x0 - 16, y - 2, z0 - 8, x0 - 1, y + 6, z1 + 8)}
        before = {}
        for name, b in strips.items():
            before[name] = {}
            for (bx, by, bz) in ts_scan.box(b):
                before[name]["%d,%d,%d" % (bx, by, bz)] = world.block(bx, by, bz)
        press_states = [world.block(*p) for p in presses]
        report["press_states_before"] = press_states
        log("actuators: %s" % ", ".join("%s %s" % (p, st) for p, st in zip(presses, press_states)))

        if a.fill != "none":
            r = s("fill %d %d %d %d %d %d %s replace air" % (x0, y, z0, x1, y, z1, a.fill))
            log("pattern: %s" % r)
            report["fill_reply"] = r

        def sample():
            rows = 0
            for z in range(z0, z1 + 1):
                if s.passed("execute if blocks %d %d %d %d %d %d %d %d %d all" % (x0, y, z, x1, y, z, x0, 300, z)):
                    rows += 1
            filled = 0
            for zz in range(z0 + 1, z1 + 1, 4):
                for xx in range(x0 + 1, x1 + 1, 4):
                    if not s.passed("execute if block %d %d %d minecraft:air" % (xx, y, zz)):
                        filled += 1
            items = s.count("execute if entity @e[type=item,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]"
                            % (x0 - 16, y - 3, z0 - 16, 160, 12, 160))
            return rows, filled, items

        rows, filled, items = sample()
        log("before press: rows clear %d/128, sampled non-air %d/1024, items %d" % (rows, filled, items))
        report["samples"].append({"t": -1, "rows_clear": rows, "sampled_non_air": filled, "items": items})

        t0 = time.time()
        for p in presses:
            if a.method == "rcon":
                st = world.block(*p)
                if "powered=false" not in st:
                    raise SystemExit("actuator at %s is %s, expected a button/lever with powered=false" % (p, st))
                on = st.replace("powered=false", "powered=true")
                log("press %s: %s" % (p, s("setblock %d %d %d %s" % (p + (on,)))))
                time.sleep(a.hold / 20.0)
                log("release %s: %s" % (p, s("setblock %d %d %d %s" % (p + (st,)))))
            else:
                cmd = [os.path.join(HERE, "zenith-run-config", "zenith-cmd.sh"), "pathfinder", "click", "right",
                       str(p[0]), str(p[1]), str(p[2]), "--wait", "1"]
                subprocess.run(cmd, check=False, stdout=subprocess.DEVNULL)
                log("asked the bot to click %s" % (p,))
            time.sleep(0.5)

        last_change = time.time()
        last = (rows, filled, items)
        while True:
            time.sleep(a.interval)
            rows, filled, items = sample()
            t = time.time() - t0
            report["samples"].append({"t": round(t, 1), "rows_clear": rows, "sampled_non_air": filled, "items": items})
            log("t=%5.1fs rows clear %3d/128, sampled non-air %4d/1024, items %d" % (t, rows, filled, items))
            if (rows, filled) != last[:2]:
                last_change = time.time()
                last = (rows, filled, items)
            if time.time() - last_change > a.settle:
                log("no change for %ds; stopping" % a.settle)
                break
            if t > a.timeout:
                log("timeout")
                break

        # Exact final state from disk.
        s("save-all flush")
        world = ts_scan.World(os.path.join(HERE, "work", "world"))
        left = {}
        for (bx, by, bz) in ts_scan.box((x0, y, z0, x1, y, z1)):
            b = world.block(bx, by, bz).split("[")[0]
            if b != "minecraft:air":
                left[b] = left.get(b, 0) + 1
        report["art_layer_left"] = left
        moved = {}
        for name, blocks in before.items():
            diffs = [(k, v, world.block(*[int(c) for c in k.split(",")])) for k, v in blocks.items()]
            diffs = [d for d in diffs if d[1] != d[2]]
            moved[name] = {"changed": len(diffs), "examples": diffs[:12]}
        report["machine_strips_changed"] = moved
        report["items_final"] = items
        report["press_states_after"] = [world.block(*p) for p in presses]
        log("art layer left: %s" % (left or "nothing (all air)"))
        for name, m in moved.items():
            log("%s strip: %d blocks differ from before the press%s" % (
                name, m["changed"], "" if not m["changed"] else " e.g. %s" % m["examples"][:3]))
        log("dropped items around the platform: %d" % items)

        if a.cleanup:
            if a.fill != "none":
                log("cleanup: %s" % s("fill %d %d %d %d %d %d air replace %s" % (x0, y, z0, x1, y, z1, a.fill)))
            log("cleanup: %s" % s("kill @e[type=item,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]"
                                  % (x0 - 16, y - 3, z0 - 16, 160, 12, 160)))
    finally:
        s("forceload remove %d %d %d %d" % (fx0, fz0, fx1, fz1))
        path = os.path.join(out_dir, "report-%s.json" % stamp)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1)
        log("report: %s" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
