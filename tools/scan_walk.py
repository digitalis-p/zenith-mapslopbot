#!/usr/bin/env python3
"""How a stock scan walked, from ZenithProxy's DEBUG log. Python 3 stdlib only; run with -I.

Usage:
  python3 -I tools/scan_walk.py run/log/debug.log [--from N]

  debug.log  ZenithProxy's debug log (Pathfinder DEBUG lines give each walk's start and goal cell).
  --from N   start reading at line N (e.g. a start line saved before `mapart scan`).

Reads from the first "scanning N material containers" line to the next phase change (or the end): containers
opened, Baritone walks, blocks walked (start to goal, |dx| + |dz|), walks that turned back along x (the old
nearest-first zig-zag turned on almost every walk), elapsed seconds, seconds per container, and the plugin's
"scan sweep: ... stand spots" line. Prints no coordinates.

Reference, moss sorter, 310 containers: before the sweep (gate B2 at 20x) 169 walks, 4,347 blocks, 166 turns,
50.9 s; at 1x on 2b2t (2026-10-11) 185 walks, about 4,700 blocks, 1,095 s. The sweep: about 13 walks, 62-93 blocks.
"""
import datetime
import re
import sys

TS = re.compile(r'^\[(\d{4}/\d\d/\d\d \d\d:\d\d:\d\d(?:\.\d+)?)\]')
SEARCH = re.compile(r'Searching for path from \[(-?\d+), (-?\d+), (-?\d+)\] to GoalNearXZ\[x=(-?\d+), y=(-?\d+), z=(-?\d+)')
OPENED = re.compile(r'Container \d+ opened')


def ts(line):
    m = TS.match(line)
    if not m:
        return None
    s = m.group(1)
    return datetime.datetime.strptime(s, '%Y/%m/%d %H:%M:%S.%f' if '.' in s else '%Y/%m/%d %H:%M:%S')


def main(argv):
    if not argv or argv[0] in ('-h', '--help'):
        print(__doc__)
        return 2
    path = argv[0]
    start_line = int(argv[argv.index('--from') + 1]) if '--from' in argv else 1
    started = False
    start = end = None
    opens = walks = turns = last_dir = 0
    blocks = 0
    sweep = None
    with open(path, errors='replace') as f:
        for n, line in enumerate(f, 1):
            if n < start_line:
                continue
            t = ts(line)
            if 'material containers (' in line and 'scanning' in line:
                if started:
                    break
                started = True
                start = t
                continue
            if not started:
                continue
            if 'Plugin.mapart' in line and 'phase ' in line and '->' in line:
                break
            if 'scan sweep:' in line:
                sweep = line.split('scan sweep:', 1)[1].strip()
            if OPENED.search(line):
                opens += 1
                end = t
            m = SEARCH.search(line)
            if m:
                fx, _, fz, gx, _, gz = map(int, m.groups())
                walks += 1
                blocks += abs(gx - fx) + abs(gz - fz)
                if gx != fx:
                    d = 1 if gx > fx else -1
                    if last_dir and d != last_dir:
                        turns += 1
                    last_dir = d
    if not started:
        print('no "scanning N material containers" line found')
        return 1
    secs = (end - start).total_seconds() if start and end else float('nan')
    print(f'containers opened: {opens}')
    print(f'walks: {walks}, blocks walked: {blocks} ({blocks / max(opens, 1):.1f} per container), turns: {turns}')
    print(f'elapsed: {secs:.1f} s, {secs / max(opens, 1):.2f} s per container (log time; times the speed factor = 1x)')
    print(f'plan: {sweep if sweep else "no scan sweep line (nearest-first build?)"}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
