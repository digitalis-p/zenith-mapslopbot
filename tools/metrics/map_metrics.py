#!/usr/bin/env python3
"""Maps-per-hour metrics from the map art plugin's ZenithProxy log. Python 3 stdlib only; run with -I.

Usage:
  python3 -I tools/metrics/map_metrics.py [--last N] [--job TEXT] [--json] LOG [LOG ...]

  LOG        a ZenithProxy log: latest.log, a rotated proxy-DATE.N.log, the same zipped (.zip) or gzipped (.gz), or
             a directory (its latest.log and proxy-*.log[.zip|.gz]; launcher.log and debug.log are left out). Files
             may come in any order: the lines are merged by their timestamps and lines repeated in two files are
             read once.
  --last N   the rolling window: maps/hour over the last N maps (default 5).
  --job TEXT print only the tiles whose job id or name contains TEXT (the session summary still covers every tile).
  --json     machine-readable output instead of the text report.

Reads the INFO lines of the plugin ([Plugin.mapart], [MapArt-Bot]) and of the client ([Client] "Disconnected:",
[Proxy] "Starting ZenithProxy") and prints, for each map (tile), where its wall time went and what BUILD and NUKE
did, then a rolling and a session summary. It never prints log text, so no coordinates reach its output.

Time accounting. Every second between two log lines goes to exactly one bucket, decided by the state after the
first of them, in this order:
  offline: restart      proxy restarting or down (a plugin load, "Starting ZenithProxy", until bot ticks start);
                        a manual disconnect followed by a restart counts here as a whole
  offline: disconnected the client disconnected ("[Client] Disconnected:"), the reconnect and the queue, until bot
                        ticks start again
  player control        bot ticks stopped while connected (a player controls the account)
  paused                a manual pause ("paused: ..." until "resumed in ..."); a pause the plugin keeps across a
                        restart lasts until "resumed in", unless the engine works first (the start script cleared it)
  safety stop           SAFETY STOP or STUCK until "resumed in"
  materials wait        the tile went back to SELECT short of materials, until it is selected again
  cover wait            FILL_MAP while blocks above the art hide it (from "waiting at the map center" ("centre" in logs before 2026-11-15) or "creating
                        the map to hold" to "nothing above the art any more", a phase change or a restart)
  eyeblossom wait       FILL_MAP while eyeblossoms show the wrong state
  <PHASE>               otherwise the engine phase from "phase X -> Y" (BLOCKED included)
  no tile               no tile selected (empty queue, before the first tile); session only

A map (tile) starts at its first "selected #job name [x,z]" and is done at "#job name [x,z] stored in" (the
plugin's "Map done"); its cycle ends when FINISH_TILE ends. Map time = start -> done, cycle = start -> end (still
running: up to the last log line). External waits = cover wait, eyeblossom wait, materials wait, paused, player
control and both offline buckets; "maps/h excl. external waits" leaves them out. BLOCKED and safety stops are the
bot's own and stay in.

maps/h: per map 3600 / cycle; last N maps = N * 3600 / (sum of their cycles; a cycle still in its clean-up counts
as far as it got); session = maps done * 3600 / (time in tiles, maps not done yet included).

BUILD, preferred source: the plugin's "tile summary #job name [x,z]: placed N (build B, repair R), from stand
spots S, walking W; lane runs L; restocks X (Y loads, Z items); setbacks K" line at Map done (exact, cut-short lane
runs included, kept across restarts). It is not used when it says "counted from mid-tile only" (a plugin build without
the counters had the tile before). Without it, from the log lines: placed = confirmed + late clicks of the lane
summaries ("Lane N: ... clicks C: X confirmed, L late"; older logs without clicks: the lane's "placed"; lane runs cut
short log no summary, so this undercounts), stand spots from "P from W stand spots"; restocks = "restock:" lines,
loads = those that withdrew items. Always from the log: lane runs ended, of which walled (a "walking line walled by
full blocks" line: every cell from stand spots) and walled starts repeated after a stop; lanes = the first restock's
"lanes left"; "making room" and "crowded load" lines. NUKE (after Map done, so never in the summary): broken = first
"nuking N blocks" minus what is left (0 once NUKE ended; else the last count minus "Break round ... D/T broken" after
it); blocks/s over the NUKE bucket.

Setbacks: the summary's exact count; without it a lower bound, as setbacks are not logged one by one: the larger of
the "Frequent setbacks: N ... in 300s" notices (their windows do not overlap) and the setbacks in back-off bursts
("backing off: ... K server setbacks").
"""

import argparse
import datetime as dt
import gzip
import json
import os
import re
import sys
import zipfile

# ---------------------------------------------------------------------------------------------------------------
# Reading

LINE_RE = re.compile(r'^\[(\d{4})/(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2})\] \[([^\]]+)\] \[([A-Z]+)\] ?(.*)$')
LOG_SUFFIXES = ('.log', '.log.zip', '.log.gz')


def read_text(path):
    """The text of a plain, .zip (every member, in name order) or .gz log."""
    if path.endswith('.zip'):
        with zipfile.ZipFile(path) as z:
            parts = []
            for name in sorted(z.namelist()):
                if name.endswith('/'):
                    continue
                parts.append(z.read(name).decode('utf-8', errors='replace'))
            return '\n'.join(parts)
    if path.endswith('.gz'):
        with gzip.open(path, 'rb') as f:
            return f.read().decode('utf-8', errors='replace')
    with open(path, 'rb') as f:
        return f.read().decode('utf-8', errors='replace')


def expand_paths(args):
    out = []
    for a in args:
        if os.path.isdir(a):
            for name in sorted(os.listdir(a)):
                if (name == 'latest.log' or name.startswith('proxy-')) and name.endswith(LOG_SUFFIXES):
                    out.append(os.path.join(a, name))
        else:
            out.append(a)
    return out


class Line:
    __slots__ = ('ts', 'src', 'level', 'msg', 'order')

    def __init__(self, ts, src, level, msg, order):
        self.ts = ts
        self.src = src
        self.level = level
        self.msg = msg
        self.order = order


def parse_files(paths):
    """Timestamped lines of every file, merged by time; a line seen in an earlier file is dropped. Returns
    (lines, files) with files = [(basename, first ts, last ts, line count)]."""
    seen = {}  # (ts, line) -> index of the file it was first read from
    lines = []
    files = []
    for rank, p in enumerate(paths):
        text = read_text(p)
        first = last = None
        n = 0
        for idx, raw in enumerate(text.splitlines()):
            m = LINE_RE.match(raw)
            if not m:
                continue  # continuation of a multi-line entry
            y, mo, d, h, mi, s = (int(m.group(i)) for i in range(1, 7))
            ts = dt.datetime(y, mo, d, h, mi, s)
            key = (ts, raw)
            if seen.setdefault(key, rank) != rank:
                continue  # read from another file already (a log given twice, or a directory and its files)
            first = first or ts
            last = ts
            n += 1
            lines.append(Line(ts, m.group(7), m.group(8), m.group(9), (rank, idx)))
        files.append((os.path.basename(p), first, last, n))
    # files in time order, then lines in time order (stable within a second: file, then line order)
    rank_by_first = {}
    for rank, f in enumerate(files):
        rank_by_first[rank] = (f[1] or dt.datetime.max, rank)
    lines.sort(key=lambda ln: (ln.ts, rank_by_first[ln.order[0]], ln.order[1]))
    return lines, files


# ---------------------------------------------------------------------------------------------------------------
# Line patterns (plugin log lines; see the BuildEngine/LaneRun/BreakRun log calls)

LABEL_RE = re.compile(r'^#(\S+) (.*) \[(-?\d+),(-?\d+)\]$')
R_PHASE = re.compile(r'^phase (\w+) -> (\w+)(?: \((#.+)\))?$')
R_SELECTED = re.compile(r'^selected (#.+?) on platform (\S+) \((\d+) blocks')
R_STARTED = re.compile(r'^\[mapart\] Started .*\(map (\d+) of (\d+)\)\s*$')
R_RESUMING = re.compile(r'^resuming (#.+) at (\w+)$')
R_RESUMED = re.compile(r'^resumed in (\w+)')
R_PAUSED = re.compile(r'^paused: ')
R_SAFETY = re.compile(r'^SAFETY STOP: ')
R_STUCK = re.compile(r'^STUCK, paused: ')
R_BLOCKED = re.compile(r'^BLOCKED \((\w+)\): ')
R_RESTORED_BLOCKED = re.compile(r'^restored BLOCKED \((\w+)\): ')
R_CHECKPOINT_BLOCKED = re.compile(r'^checkpoint was BLOCKED \((\w+): ')
R_ENGINE_READY = re.compile(r'^engine ready \(idle\)')
R_TICKS_START = re.compile(r'^bot ticks starting \((\w+)(?:, (#.+))?\)$')
R_TICKS_STOP = re.compile(r'^bot ticks stopped \((\w+)\)')
R_PLUGIN_LOADED = re.compile(r'^Map art plugin (\S+) \(([^)]*)\) loaded')
R_STORED = re.compile(r'^(#.+?) stored in ')
# the plugin's own per-tile counters, one line at Map done (BuildEngine.tileSummary)
R_SUMMARY = re.compile(r'^tile summary (#.+?): placed (\d+) \(build (\d+), repair (\d+)\), from stand spots (\d+), '
                       r'walking (\d+); lane runs (\d+); restocks (\d+) \((\d+) loads, (\d+) items\); setbacks (\d+)'
                       r'(; counted from mid-tile only)?')
R_SHORT = re.compile(r'^(#.+?) short of materials: ')
R_COVER_START = re.compile(r'; waiting at the map cent(?:er|re)( until they are gone| up to \d+ min)$')
R_COVER_HOLD = re.compile(r': creating the map to hold while the art is covered')
R_COVER_AGAIN = re.compile(r': covered again \d+ s after it cleared: the wait goes on')
R_COVER_END = re.compile(r': nothing above the art any more after (\d+) s')
R_BLOSSOM_START = re.compile(r': \d+ of \d+ eyeblossoms are \w+; waiting up to \d+ min at the map cent(?:er|re)')
R_BLOSSOM_END = re.compile(r'^eyeblossoms are \w+ after \d+ s; filling the map')
R_LANE_DONE = re.compile(r'^Lane (\d+): (\d+) placed, (\d+) skipped of (\d+) in (\d+) ticks(.*)$')
R_CLICKS = re.compile(r'clicks (\d+): (\d+) confirmed, (\d+) late, (\d+) rejected')
R_SPOTS = re.compile(r'; (\d+) from (\d+) stand spots')
R_WALLED = re.compile(r'^Lane (\d+): walking line walled by full blocks; (\d+) cells from stand spots')
R_TO_SPOTS = re.compile(r'^Lane (\d+): (\d+) cells to place from stand spots(?: \((\d+) passed out of reach)?')
R_RESTOCK = re.compile(r'^restock: (\d+) lanes left, load covers (\d+)(?: \+ (\d+) cells of the next)?, withdraw \{([^}]*)\}')
R_ITEM_COUNT = re.compile(r'=(\d+)')
R_MAKING_ROOM = re.compile(r'^making room: ')
R_CROWDED = re.compile(r'^crowded load: ')
R_VERIFY = re.compile(r'^verify pass (\d+)/(\d+): (\d+) wrong, (\d+) missing')
R_NUKING = re.compile(r'^nuking (\d+) blocks on ')
R_BREAK_ROUND = re.compile(r'^Break round \((\w+)\) (\w+): (\d+)/(\d+) broken in (\d+) ticks')
R_BACKOFF = re.compile(r'^backing off: standing still for (\d+) s \((\w+)\): (\d+) server setbacks in ')
R_FREQUENT = re.compile(r'^\[mapart\] Frequent setbacks: (\d+) small server teleports in (\d+)s')
R_ESCAPE_START = re.compile(r'^escape rung \d+(?: of \d+)? at .*stepping away')
R_ESCAPE_DONE = re.compile(r'^escape rung \d+(?: of \d+)? done: ')
R_ESCAPE_FAILED = re.compile(r'^escape rung \d+(?: of \d+)? failed: ')
R_ESCAPE_DROPPED = re.compile(r'^escape rung \d+(?: of \d+)? dropped: ')
R_KEEPALIVE = re.compile(r'^keep-alive: idle \d+ s')
# lines that only a working (not paused) engine logs: they settle a pause carried across a restart
R_WORK = re.compile(r'^(phase \w+ -> |restock: |Lane \d+: |nuking \d+ |creating the map|Break round |map locked|'
                    r'map renamed|verify pass |dropping \{|making room: |crowded load: |Breaking \d+ blocks|'
                    r'fetching \d+ XP|mending |scanning \d+ |scan sweep|restock sweep|selected #|holding map |'
                    r'placing \d+ missing|breaking \d+ wrong|.* cut short: )')

PHASES = ['IDLE', 'SELECT', 'INSPECT', 'STOCK_CHECK', 'GEAR', 'RESTOCK', 'BUILD', 'VERIFY', 'FILL_MAP', 'CHECK_MAP',
          'LOCK', 'RENAME', 'STORE', 'COUNT', 'NUKE', 'CLEAR', 'WAIT_CLEAR', 'FINISH_TILE', 'BLOCKED']
TILE_END_PHASES = ('FINISH_TILE',)
# display order of the buckets; FILL_MAP is shown with its waits included
BUCKET_ORDER = PHASES[1:] + ['COVER_WAIT', 'BLOSSOM_WAIT', 'IDLE', 'UNKNOWN', 'SAFETY_STOP', 'PAUSED', 'MATERIALS_WAIT',
                             'REQUEUED', 'CONTROL', 'OFFLINE', 'RESTART', 'NO_TILE']
BUCKET_LABEL = {
    'COVER_WAIT': 'cover wait', 'BLOSSOM_WAIT': 'eyeblossom wait', 'SAFETY_STOP': 'safety stop',
    'PAUSED': 'paused', 'MATERIALS_WAIT': 'materials wait', 'REQUEUED': 'back in the queue', 'CONTROL': 'player control',
    'OFFLINE': 'offline: disconnected', 'RESTART': 'offline: restart', 'NO_TILE': 'no tile (idle)',
    'IDLE': 'IDLE (in tile)', 'UNKNOWN': 'unknown phase',
}
EXTERNAL = ('COVER_WAIT', 'BLOSSOM_WAIT', 'MATERIALS_WAIT', 'PAUSED', 'CONTROL', 'OFFLINE', 'RESTART')
# a "bot ticks stopped" at most this long before "Disconnected:" belongs to the disconnect, not to a player
CONTROL_MERGE_SECONDS = 2
EVENT_KEYS = ['setback_notices', 'setbacks_noticed', 'setback_peak', 'backoffs', 'backoff_setbacks', 'backoff_seconds',
              'escapes', 'escapes_done', 'escapes_failed', 'escapes_dropped', 'safety_stops', 'stuck', 'blocked',
              'keepalives', 'pauses', 'disconnects', 'disconnects_manual', 'disconnects_connection',
              'disconnects_other', 'reconnect_attempts_failed', 'reconnects', 'reconnect_seconds', 'restarts',
              'restart_seconds', 'controls', 'control_seconds']


def label_key(label):
    """(job id, name, tile x, tile z) of '#j0002 ellen [0,0]', or None."""
    m = LABEL_RE.match(label.strip())
    if not m:
        return None
    return m.group(1), m.group(2), int(m.group(3)), int(m.group(4))


def disconnect_kind(reason):
    r = reason.lower()
    if 'manual disconnect' in r:
        return 'manual'
    if any(k in r for k in ('connection reset', 'timed out', 'timeout', 'connection refused', 'nativeioexception',
                            'connection closed', 'broken pipe', 'end of stream')):
        return 'connection'
    return 'other'


# ---------------------------------------------------------------------------------------------------------------
# Model


class Counters:
    def __init__(self):
        for k in EVENT_KEYS:
            setattr(self, k, 0)

    def as_dict(self):
        return {k: getattr(self, k) for k in EVENT_KEYS}

    def setbacks_min(self):
        return max(self.setbacks_noticed, self.backoff_setbacks)


class Tile:
    def __init__(self, job, name, x, z, start, partial):
        self.job = job
        self.name = name
        self.x = x
        self.z = z
        self.start = start
        self.partial_start = partial
        self.platform = None
        self.blocks = None
        self.map_index = None
        self.map_count = None
        self.map_done = None
        self.end = None
        self.last_seen = start
        self.before = {}   # bucket -> seconds before the map was done
        self.after = {}    # bucket -> seconds after
        self.events = Counters()
        self.short_pending = False
        self.summary = None  # the plugin's "tile summary" counters (dict), when it logged one
        # BUILD
        self.lane_runs = 0
        self.lane_runs_walled = 0
        self.lane_runs_restarted = 0
        self.walled_open = set()
        self.placed = 0
        self.placed_reported = 0
        self.spot_placed = 0
        self.spot_walks = 0
        self.passed_out_of_reach = 0
        self.lanes_total = 0
        self.loads_build = 0
        self.loads_repair = 0
        self.loads_empty = 0
        self.withdrawn = 0
        self.covers_lanes = 0
        self.covers_cells = 0
        self.making_room = 0
        self.crowded = 0
        self.seen_verify = False
        # VERIFY
        self.verify_passes = 0
        self.verify_wrong = 0
        self.verify_missing = 0
        self.verify_placed = 0
        self.verify_broken = 0
        # NUKE
        self.nuke_first = None
        self.nuke_last = None
        self.nuke_broken_after_last = 0
        self.nuke_done = False
        self.nuke_rounds = 0

    @property
    def key(self):
        return self.job, self.x, self.z

    def label(self):
        return '#%s %s [%d,%d]' % (self.job, self.name, self.x, self.z)

    def add(self, bucket, secs, after):
        d = self.after if after else self.before
        d[bucket] = d.get(bucket, 0) + secs

    def bucket_total(self, bucket):
        return self.before.get(bucket, 0) + self.after.get(bucket, 0)

    def map_seconds(self):
        return sum(self.before.values())

    def cycle_seconds(self):
        return sum(self.before.values()) + sum(self.after.values())

    def external_seconds(self, part=None):
        ds = [self.before, self.after] if part is None else [self.before if part == 'before' else self.after]
        return sum(d.get(b, 0) for d in ds for b in EXTERNAL)

    def exact(self):
        """The plugin's tile summary when it counted the whole tile (preferred over the log-derived values)."""
        return self.summary if self.summary and not self.summary['partial'] else None

    def nuke_broken(self):
        if self.nuke_first is None:
            return None
        left = 0 if self.nuke_done else max(0, (self.nuke_last or 0) - self.nuke_broken_after_last)
        return max(0, self.nuke_first - left)


class Episode:
    """A stretch with bot ticks stopped: 'offline' (disconnected or restarting) or 'control' (a player in control)."""

    def __init__(self, kind, start, tile, reason=None, restart=False, initial=False):
        self.kind = kind
        self.start = start
        self.tile = tile
        self.reason = reason
        self.restart = restart
        self.initial = initial
        self.pending = []  # (tile, after-done flag, seconds): offline time, bucket known at the end


class Analyzer:
    def __init__(self, lines):
        self.lines = lines
        self.tiles = []
        self.by_key = {}
        self.session = {}
        self.events = Counters()
        self.builds = []
        self.phase = None
        self.tile = None
        self.suspended = None
        self.suspend_reason = None
        self.ticking = True
        self.connected = True
        self.paused = None
        self.cover = False
        self.blossom = False
        self.episode = None
        self.ever_ticked = False
        self.last_ts = None
        self.first_ts = lines[0].ts if lines else None
        self.end_ts = lines[-1].ts if lines else None
        self.unpaused_at_load = self._prescan_loads()

    # -- pre-scan: does a pause carried across a restart still hold? --------------------------------------------

    def _prescan_loads(self):
        """Indexes of plugin-load lines after which the engine worked before any "resumed in" / new pause."""
        out = set()
        for i, ln in enumerate(self.lines):
            if ln.src != 'Plugin.mapart' or not R_PLUGIN_LOADED.match(ln.msg):
                continue
            for later in self.lines[i + 1:]:
                if later.src not in ('Plugin.mapart', 'MapArt-Bot'):
                    continue
                msg = later.msg
                if R_PLUGIN_LOADED.match(msg) or R_RESUMED.match(msg) or R_PAUSED.match(msg) \
                        or R_SAFETY.match(msg) or R_STUCK.match(msg):
                    break
                if R_WORK.match(msg):
                    out.add(i)
                    break
        return out

    # -- accounting ----------------------------------------------------------------------------------------------

    def current(self):
        return self.tile or self.suspended

    def _add(self, tile, after, bucket, secs):
        self.session[bucket] = self.session.get(bucket, 0) + secs
        if tile is not None:
            tile.add(bucket, secs, after)

    def account(self, t0, t1):
        secs = int((t1 - t0).total_seconds())
        if secs <= 0:
            return
        tile = self.current()
        after = tile is not None and tile.map_done is not None
        if not self.ticking:
            if self.episode is not None and self.episode.kind == 'offline':
                self.episode.pending.append((tile, after, secs))
                return
            bucket = 'CONTROL' if self.connected else 'OFFLINE'
        elif self.paused == 'manual':
            bucket = 'PAUSED'
        elif self.paused == 'safety':
            bucket = 'SAFETY_STOP'
        elif tile is None:
            bucket = 'NO_TILE'
        elif self.tile is None:
            bucket = 'MATERIALS_WAIT' if self.suspend_reason == 'materials' else 'REQUEUED'
        elif self.phase == 'FILL_MAP' and self.cover:
            bucket = 'COVER_WAIT'
        elif self.phase == 'FILL_MAP' and self.blossom:
            bucket = 'BLOSSOM_WAIT'
        elif self.phase in PHASES:
            bucket = self.phase
        else:
            bucket = 'UNKNOWN'
        self._add(tile, after, bucket, secs)

    def count(self, name, n=1):
        setattr(self.events, name, getattr(self.events, name) + n)
        t = self.current()
        if t is not None:
            setattr(t.events, name, getattr(t.events, name) + n)

    # -- episodes ------------------------------------------------------------------------------------------------

    def open_offline(self, ts, reason=None, restart=False):
        ep = self.episode
        if ep is not None and ep.kind == 'control' and (ts - ep.start).total_seconds() <= CONTROL_MERGE_SECONDS:
            # "bot ticks stopped" logged just before "Disconnected:": the disconnect stopped the ticks
            self.episode = None
            ts = ep.start
        self.close_control(ts)
        self.episode = Episode('offline', ts, self.current(), reason=reason, restart=restart,
                               initial=restart and not self.ever_ticked)

    def close_control(self, ts):
        ep = self.episode
        if ep is None or ep.kind != 'control':
            return
        self.episode = None
        self._episode_count(ep, 'controls', 'control_seconds', ts)

    def _episode_count(self, ep, count_key, secs_key, ts):
        secs = int((ts - ep.start).total_seconds())
        setattr(self.events, count_key, getattr(self.events, count_key) + 1)
        setattr(self.events, secs_key, getattr(self.events, secs_key) + secs)
        if ep.tile is not None:
            setattr(ep.tile.events, count_key, getattr(ep.tile.events, count_key) + 1)
            setattr(ep.tile.events, secs_key, getattr(ep.tile.events, secs_key) + secs)

    def close_offline(self, ts):
        ep = self.episode
        if ep is None or ep.kind != 'offline':
            return
        self.episode = None
        if ep.restart:
            bucket = 'RESTART'
        else:
            bucket = 'OFFLINE'
        for tile, after, secs in ep.pending:
            b = 'NO_TILE' if (ep.initial and tile is None) else bucket
            self._add(tile, after, b, secs)
        if ts is None:
            return  # still offline at the end of the logs
        if ep.initial:
            return
        if ep.restart:
            self._episode_count(ep, 'restarts', 'restart_seconds', ts)
        else:
            self._episode_count(ep, 'reconnects', 'reconnect_seconds', ts)

    # -- tiles ---------------------------------------------------------------------------------------------------

    def tile_for(self, label, ts, create=True):
        k = label_key(label)
        if k is None:
            return None
        job, name, x, z = k
        t = self.by_key.get((job, x, z))
        if t is not None and t.end is None:
            return t
        if not create:
            return t
        t = Tile(job, name, x, z, ts, partial=True)
        self.by_key[(job, x, z)] = t
        self.tiles.append(t)
        return t

    def adopt(self, label, ts):
        """A line names a tile while none is current (the logs start mid-tile, or after a restart)."""
        t = self.tile_for(label, ts)
        if t is None:
            return
        if self.suspended is t:
            self.suspended = None
        if self.tile is None:
            self.tile = t

    def finish_tile(self, ts):
        t = self.tile or self.suspended
        if t is not None:
            t.end = ts
        self.tile = None
        self.suspended = None
        self.cover = self.blossom = False

    # -- main loop -----------------------------------------------------------------------------------------------

    def run(self):
        for i, ln in enumerate(self.lines):
            restart = ln.src == 'Proxy' and ln.msg.startswith('Starting ZenithProxy')
            plugin_load = ln.src == 'Plugin.mapart' and R_PLUGIN_LOADED.match(ln.msg)
            if restart or plugin_load:
                self.on_restart(ln, i, bool(plugin_load))
            if self.last_ts is not None:
                self.account(self.last_ts, ln.ts)
            self.last_ts = ln.ts
            t = self.current()
            if t is not None:
                t.last_seen = ln.ts
            if ln.src == 'Client':
                self.on_client(ln)
            elif ln.src in ('Plugin.mapart', 'MapArt-Bot'):
                self.on_plugin(ln)
        # the end: open episodes are resolved with what is known
        if self.episode is not None:
            if self.episode.kind == 'offline':
                self.close_offline(None)
            else:
                ep = self.episode
                self.episode = None
                self._episode_count(ep, 'controls', 'control_seconds', self.end_ts)
        return self

    def on_restart(self, ln, index, plugin_load):
        if plugin_load:
            m = R_PLUGIN_LOADED.match(ln.msg)
            self.builds.append((ln.ts, m.group(1), m.group(2)))
            if self.paused and index in self.unpaused_at_load:
                self.paused = None
        if self.episode is not None and self.episode.kind == 'offline':
            self.episode.restart = True
        else:
            start = self.last_ts if self.last_ts is not None else ln.ts
            self.open_offline(start, reason='restart', restart=True)
        self.ticking = False
        self.connected = False
        self.cover = self.blossom = False

    def on_client(self, ln):
        msg = ln.msg
        if msg.startswith('Disconnected: '):
            reason = msg[len('Disconnected: '):]
            if self.episode is not None and self.episode.kind == 'offline':
                if 'already connected' in reason.lower():
                    self.count('reconnect_attempts_failed')
                return
            kind = disconnect_kind(reason)
            self.count('disconnects')
            self.count('disconnects_' + kind)
            self.open_offline(ln.ts, reason=kind)
            self.ticking = False
            self.connected = False

    def on_plugin(self, ln):
        msg = ln.msg
        ts = ln.ts

        m = R_TICKS_START.match(msg)
        if m:
            self.close_control(ts)
            self.close_offline(ts)
            self.ticking = True
            self.connected = True
            self.ever_ticked = True
            if m.group(2):
                self.adopt(m.group(2), ts)
                self.phase = m.group(1)
            elif self.current() is None:
                self.phase = m.group(1)
            # else: a restarted engine says IDLE until it restores the tile ("resuming ... at PHASE"): keep the phase
            return
        if R_TICKS_STOP.match(msg):
            if self.ticking and self.connected and self.episode is None:
                self.episode = Episode('control', ts, self.current())
            self.ticking = False
            return

        m = R_PHASE.match(msg)
        if m:
            self.on_phase(m.group(1), m.group(2), m.group(3), ts)
            return
        m = R_SELECTED.match(msg)
        if m:
            self.on_selected(m.group(1), m.group(2), int(m.group(3)), ts)
            return
        m = R_STARTED.match(msg)
        if m:
            if self.tile is not None and self.tile.map_index is None:
                self.tile.map_index = int(m.group(1))
                self.tile.map_count = int(m.group(2))
            return
        m = R_RESUMING.match(msg)
        if m:
            self.adopt(m.group(1), ts)
            self.phase = m.group(2)
            return
        m = R_RESUMED.match(msg)
        if m:
            self.paused = None
            self.phase = m.group(1)
            return
        if R_PAUSED.match(msg):
            if self.paused is None:
                self.count('pauses')
                self.paused = 'manual'
            return
        if R_SAFETY.match(msg):
            self.count('safety_stops')
            self.paused = 'safety'
            return
        if R_STUCK.match(msg):
            self.count('stuck')
            self.paused = 'safety'
            return
        if R_BLOCKED.match(msg):
            self.count('blocked')
            self.phase = 'BLOCKED'
            self.cover = self.blossom = False
            return
        if R_RESTORED_BLOCKED.match(msg):
            self.phase = 'BLOCKED'
            return
        m = R_CHECKPOINT_BLOCKED.match(msg)
        if m:
            self.phase = m.group(1)
            return
        if R_ENGINE_READY.match(msg):
            self.phase = 'IDLE'
            return
        m = R_STORED.match(msg)
        if m:
            t = self.tile_for(m.group(1), ts, create=False) or self.tile
            if t is not None and t.map_done is None:
                t.map_done = ts
            return
        m = R_SUMMARY.match(msg)
        if m:
            t = self.tile_for(m.group(1), ts, create=False) or self.tile
            if t is not None:
                n = [int(m.group(i)) for i in range(2, 12)]
                t.summary = dict(zip(('placed', 'build', 'repair', 'from_stand_spots', 'walking', 'lane_runs',
                                      'restocks', 'loads', 'withdrawn', 'setbacks'), n), partial=bool(m.group(12)))
            return
        m = R_SHORT.match(msg)
        if m:
            t = self.tile_for(m.group(1), ts, create=False) or self.tile
            if t is not None:
                t.short_pending = True
            return

        # FILL_MAP waits
        if 'eyeblossoms are' in msg and R_BLOSSOM_START.search(msg):
            self.blossom = True
            return
        if R_BLOSSOM_END.match(msg):
            self.blossom = False
            return
        if R_COVER_START.search(msg) or R_COVER_HOLD.search(msg) or R_COVER_AGAIN.search(msg):
            self.cover = True
            self.blossom = False
            return
        if R_COVER_END.search(msg):
            self.cover = False
            return

        # setbacks, back-offs, escapes, keep-alive
        m = R_BACKOFF.match(msg)
        if m:
            self.count('backoffs')
            self.count('backoff_seconds', int(m.group(1)))
            self.count('backoff_setbacks', int(m.group(3)))
            return
        m = R_FREQUENT.match(msg)
        if m:
            n = int(m.group(1))
            self.count('setback_notices')
            self.count('setbacks_noticed', n)
            for c in (self.events, self.current().events if self.current() else None):
                if c is not None and n > c.setback_peak:
                    c.setback_peak = n
            return
        if R_ESCAPE_START.match(msg):
            self.count('escapes')
            return
        if R_ESCAPE_DONE.match(msg):
            self.count('escapes_done')
            return
        if R_ESCAPE_FAILED.match(msg):
            self.count('escapes_failed')
            return
        if R_ESCAPE_DROPPED.match(msg):
            self.count('escapes_dropped')
            return
        if R_KEEPALIVE.match(msg):
            self.count('keepalives')
            return

        t = self.tile
        if t is None:
            return
        # BUILD / VERIFY / NUKE detail
        m = R_WALLED.match(msg)
        if m:
            lane = int(m.group(1))
            if lane in t.walled_open:
                t.lane_runs_restarted += 1
            t.walled_open.add(lane)
            return
        m = R_TO_SPOTS.match(msg)
        if m:
            if m.group(3):
                t.passed_out_of_reach += int(m.group(3))
            return
        m = R_LANE_DONE.match(msg)
        if m:
            lane = int(m.group(1))
            placed = int(m.group(2))
            rest = m.group(6)
            c = R_CLICKS.search(rest)
            landed = int(c.group(2)) + int(c.group(3)) if c else placed
            if self.phase == 'VERIFY':
                t.verify_placed += landed
                t.walled_open.discard(lane)
                return
            t.lane_runs += 1
            if lane in t.walled_open:
                t.lane_runs_walled += 1
                t.walled_open.discard(lane)
            t.placed += landed
            t.placed_reported += placed
            s = R_SPOTS.search(rest)
            if s:
                t.spot_placed += int(s.group(1))
                t.spot_walks += int(s.group(2))
            return
        m = R_RESTOCK.match(msg)
        if m:
            lanes_left = int(m.group(1))
            items = sum(int(x) for x in R_ITEM_COUNT.findall(m.group(4)))
            if t.seen_verify:
                t.loads_repair += 1
            else:
                t.loads_build += 1
                t.lanes_total = max(t.lanes_total, lanes_left)
                t.covers_lanes += int(m.group(2))
                t.covers_cells += int(m.group(3) or 0)
            if items == 0:
                t.loads_empty += 1
            t.withdrawn += items
            return
        if R_MAKING_ROOM.match(msg):
            t.making_room += 1
            return
        if R_CROWDED.match(msg):
            t.crowded += 1
            return
        m = R_VERIFY.match(msg)
        if m:
            t.verify_passes += 1
            t.verify_wrong += int(m.group(3))
            t.verify_missing += int(m.group(4))
            return
        m = R_NUKING.match(msg)
        if m:
            n = int(m.group(1))
            if t.nuke_first is None:
                t.nuke_first = n
            t.nuke_last = n
            t.nuke_broken_after_last = 0
            t.nuke_rounds += 1
            return
        m = R_BREAK_ROUND.match(msg)
        if m:
            done = int(m.group(3))
            if self.phase == 'NUKE':
                t.nuke_broken_after_last += done
            elif self.phase == 'VERIFY':
                t.verify_broken += done
            return

    def on_phase(self, frm, to, label, ts):
        t = self.tile
        if t is None and label and to not in ('IDLE', 'SELECT'):
            self.adopt(label, ts)
            t = self.tile
        if frm in TILE_END_PHASES:
            if t is not None and frm == 'FINISH_TILE':
                self.finish_tile(ts)
        elif t is not None and to in ('SELECT', 'IDLE') and frm not in ('IDLE', 'SELECT'):
            # back to the queue without finishing: short of materials (or the job went back to waiting)
            self.suspended = t
            self.suspend_reason = 'materials' if t.short_pending else 'requeued'
            t.short_pending = False
            self.tile = None
        if t is not None and frm == 'NUKE' and to != 'BLOCKED':
            t.nuke_done = True
        if t is not None and to == 'VERIFY':
            t.seen_verify = True
        if to != 'FILL_MAP':
            self.cover = self.blossom = False
        self.phase = to

    def on_selected(self, label, platform, blocks, ts):
        k = label_key(label)
        if k is None:
            return
        job, name, x, z = k
        key = (job, x, z)
        if self.tile is not None and self.tile.key != key:
            self.tile = None  # left unfinished; its record stays
        if self.suspended is not None and self.suspended.key != key:
            self.suspended = None
        t = self.by_key.get(key)
        if t is None or t.end is not None:
            t = Tile(job, name, x, z, ts, partial=False)
            self.by_key[key] = t
            self.tiles.append(t)
        if self.suspended is t:
            self.suspended = None
        self.tile = t
        t.platform = platform
        t.blocks = blocks


# ---------------------------------------------------------------------------------------------------------------
# Report


def fmt_dur(s):
    if s is None:
        return '-'
    s = int(round(s))
    sign = '-' if s < 0 else ''
    s = abs(s)
    return '%s%d:%02d:%02d' % (sign, s // 3600, (s % 3600) // 60, s % 60)


def fmt_ts(t):
    return t.strftime('%Y/%m/%d %H:%M:%S') if t else '-'


def per_hour(n, secs):
    if not secs or secs <= 0:
        return None
    return n * 3600.0 / secs


def fmt_rate(v, digits=2):
    return '-' if v is None else ('%.' + str(digits) + 'f') % v


def tile_cycle_state(t):
    if t.end is not None:
        return 'finished'
    if t.map_done is not None:
        return 'clean-up running'
    return 'in progress'


def rolling(tiles_done, upto_index, n):
    """maps/h over the last n done maps up to tiles_done[upto_index]: (maps, cycle secs, external secs)."""
    window = tiles_done[max(0, upto_index - n + 1):upto_index + 1]
    cycle = sum(t.cycle_seconds() for t in window)
    ext = sum(t.external_seconds() for t in window)
    return len(window), cycle, ext


def build_numbers(t):
    """BUILD numbers: the plugin's tile summary when it counted the whole tile, else from the log lines (placed =
    confirmed clicks of the lane runs that logged a summary; restocks = "restock:" lines, loads = those that withdrew)."""
    s = t.exact()
    restock_lines = t.loads_build + t.loads_repair
    if s:
        n = {'source': 'plugin tile summary', 'placed': s['build'], 'repaired': s['repair'],
             'from_stand_spots': s['from_stand_spots'], 'walking': s['walking'], 'lane_runs_started': s['lane_runs'],
             'restocks': s['restocks'], 'loads': s['loads'], 'withdrawn': s['withdrawn'], 'setbacks': s['setbacks']}
    else:
        n = {'source': 'log lines', 'placed': t.placed, 'repaired': t.verify_placed, 'from_stand_spots': t.spot_placed,
             'walking': t.placed + t.verify_placed - t.spot_placed, 'lane_runs_started': None,
             'restocks': restock_lines, 'loads': restock_lines - t.loads_empty, 'withdrawn': t.withdrawn,
             'setbacks': None}
    secs = t.bucket_total('BUILD')
    n['seconds'] = secs
    n['blocks_per_second'] = (n['placed'] / secs) if secs else None
    n['loads_per_lane'] = (n['loads'] / t.lanes_total) if t.lanes_total else None
    n['placed_per_load'] = (n['placed'] / n['loads']) if n['loads'] else None
    n['withdrawn_per_load'] = (n['withdrawn'] / n['loads']) if n['loads'] else None
    return n


def tile_dict(t, window_info=None):
    buckets = {}
    for b in BUCKET_ORDER:
        bt, at = t.before.get(b, 0), t.after.get(b, 0)
        if bt or at:
            buckets[b] = {'to_map_done': bt, 'after_map_done': at}
    broken = t.nuke_broken()
    nuke_secs = t.bucket_total('NUKE')
    bn = build_numbers(t)
    d = {
        'job': t.job, 'name': t.name, 'tile': [t.x, t.z], 'platform': t.platform, 'blocks': t.blocks,
        'map_index': t.map_index, 'map_count': t.map_count,
        'start': t.start.isoformat(), 'start_before_logs': t.partial_start,
        'map_done': t.map_done.isoformat() if t.map_done else None,
        'end': t.end.isoformat() if t.end else None, 'state': tile_cycle_state(t),
        'map_seconds': t.map_seconds() if t.map_done else None,
        'cycle_seconds': t.cycle_seconds(),
        'external_seconds': t.external_seconds(),
        'external_seconds_to_map_done': t.external_seconds('before'),
        'buckets': buckets,
        'build': dict(bn, **{
            'placed_log': t.placed, 'placed_reported_by_lanes': t.placed_reported,
            'lane_runs_ended': t.lane_runs, 'lane_runs_walled': t.lane_runs_walled,
            'lane_runs_walked': t.lane_runs - t.lane_runs_walled, 'lane_runs_restarted': t.lane_runs_restarted,
            'stand_spot_placed_log': t.spot_placed, 'stand_spot_walks': t.spot_walks,
            'passed_out_of_reach': t.passed_out_of_reach, 'lanes': t.lanes_total,
            'restock_lines': t.loads_build, 'restock_lines_repair': t.loads_repair, 'restock_lines_empty': t.loads_empty,
            'covers_lanes_per_load': (t.covers_lanes / t.loads_build) if t.loads_build else None,
            'covers_cells_per_load': (t.covers_cells / t.loads_build) if t.loads_build else None,
            'making_room': t.making_room, 'crowded_load': t.crowded,
        }),
        'plugin_summary': t.summary,
        'verify': {'passes': t.verify_passes, 'wrong': t.verify_wrong, 'missing': t.verify_missing,
                   'placed': bn['repaired'], 'broken': t.verify_broken,
                   'seconds': t.bucket_total('VERIFY')},
        'nuke': {'seconds': nuke_secs, 'targets': t.nuke_first, 'broken': broken, 'done': t.nuke_done,
                 'rounds': t.nuke_rounds,
                 'blocks_per_second': (broken / nuke_secs) if (broken is not None and nuke_secs) else None},
        'events': dict(t.events.as_dict(), setbacks_min=t.events.setbacks_min(), setbacks=bn['setbacks']),
        'maps_per_hour': per_hour(1, t.cycle_seconds()) if t.map_done else None,
        'maps_per_hour_excl_external': per_hour(1, t.cycle_seconds() - t.external_seconds()) if t.map_done else None,
    }
    if window_info:
        d['rolling'] = window_info
    return d


def events_line(e, indent, exact_setbacks=None):
    log_part = '%d notices, peak %d in 300 s; %d in back-off bursts' % (e.setback_notices, e.setback_peak,
                                                                       e.backoff_setbacks)
    parts = [
        ('setbacks %d (plugin count; log: %s)' % (exact_setbacks, log_part)) if exact_setbacks is not None
        else 'setbacks >= %d (%s)' % (e.setbacks_min(), log_part),
        'back-offs %d (%s standing)' % (e.backoffs, fmt_dur(e.backoff_seconds)),
        'escapes %d (%d done, %d failed, %d dropped)' % (e.escapes, e.escapes_done, e.escapes_failed,
                                                        e.escapes_dropped),
        'SAFETY STOP %d, STUCK %d, BLOCKED %d, pauses %d' % (e.safety_stops, e.stuck, e.blocked, e.pauses),
        'keep-alives %d' % e.keepalives,
        'disconnects %d (%d manual, %d connection, %d other; %d failed reconnect attempts)'
        % (e.disconnects, e.disconnects_manual, e.disconnects_connection, e.disconnects_other,
           e.reconnect_attempts_failed),
        'reconnects %d (%s down), restarts %d (%s down), player control %d (%s)'
        % (e.reconnects, fmt_dur(e.reconnect_seconds), e.restarts, fmt_dur(e.restart_seconds), e.controls,
           fmt_dur(e.control_seconds)),
    ]
    return '\n'.join(indent + p for p in parts)


def bucket_rows(before, after, with_split):
    """Rows (label, before, after, total) in display order; FILL_MAP includes its waits, shown below it."""
    rows = []
    fill = ('FILL_MAP', 'COVER_WAIT', 'BLOSSOM_WAIT')
    for b in BUCKET_ORDER:
        if b in ('COVER_WAIT', 'BLOSSOM_WAIT'):
            continue
        if b == 'FILL_MAP':
            bt = sum(before.get(x, 0) for x in fill)
            at = sum(after.get(x, 0) for x in fill)
            if bt or at:
                rows.append(('FILL_MAP (incl. waits)', bt, at))
                for w in ('COVER_WAIT', 'BLOSSOM_WAIT'):
                    wb, wa = before.get(w, 0), after.get(w, 0)
                    if wb or wa:
                        rows.append(('  of which ' + BUCKET_LABEL[w], wb, wa))
            continue
        bt, at = before.get(b, 0), after.get(b, 0)
        if bt or at:
            rows.append((BUCKET_LABEL.get(b, b), bt, at))
    return rows


def render_table(rows, total, with_split, indent='  '):
    out = []
    if with_split:
        out.append('%s%-26s %10s %10s %10s %6s' % (indent, 'time', 'to done', 'after', 'total', 'share'))
    else:
        out.append('%s%-26s %10s %6s' % (indent, 'time', 'total', 'share'))
    for label, bt, at in rows:
        tot = bt + at
        share = '%5.1f%%' % (100.0 * tot / total) if total else '     -'
        if with_split:
            out.append('%s%-26s %10s %10s %10s %6s' % (indent, label, fmt_dur(bt) if bt else '',
                                                        fmt_dur(at) if at else '', fmt_dur(tot), share))
        else:
            out.append('%s%-26s %10s %6s' % (indent, label, fmt_dur(tot), share))
    return out


def render_tile(t, roll, last_n):
    lines = []
    head = t.label()
    if t.map_index:
        head += ' (map %d of %d)' % (t.map_index, t.map_count)
    if t.platform:
        head += ' on ' + t.platform
    if t.blocks:
        head += ', %d blocks' % t.blocks
    lines.append(head)
    start = fmt_ts(t.start) + (' (or earlier: the logs start mid-tile)' if t.partial_start else '')
    lines.append('  start      %s' % start)
    if t.map_done:
        lines.append('  map done   %s   map time %s (excl. external waits %s)'
                     % (fmt_ts(t.map_done), fmt_dur(t.map_seconds()),
                        fmt_dur(t.map_seconds() - t.external_seconds('before'))))
    else:
        lines.append('  map done   - (not yet; %s so far)' % fmt_dur(t.map_seconds()))
    if t.end:
        lines.append('  cycle end  %s   cycle %s' % (fmt_ts(t.end), fmt_dur(t.cycle_seconds())))
    else:
        lines.append('  cycle end  - (%s; %s so far, up to %s)' % (tile_cycle_state(t), fmt_dur(t.cycle_seconds()),
                                                                     fmt_ts(t.last_seen)))
    total = t.cycle_seconds()
    lines.extend(render_table(bucket_rows(t.before, t.after, True), total, True))
    ext_b, ext_a = t.external_seconds('before'), t.external_seconds('after')
    lines.append('  %-26s %10s %10s %10s %6s' % ('external waits', fmt_dur(ext_b), fmt_dur(ext_a),
                                                 fmt_dur(ext_b + ext_a),
                                                 ('%5.1f%%' % (100.0 * (ext_b + ext_a) / total)) if total else ''))
    lines.append('  %-26s %10s %10s %10s' % ('total', fmt_dur(t.map_seconds()), fmt_dur(sum(t.after.values())),
                                              fmt_dur(total)))
    # BUILD
    bn = build_numbers(t)
    if t.lane_runs or t.loads_build or bn['seconds'] or t.exact():
        lines.append('  BUILD   %d placed (+%d in VERIFY) in %s (%s blocks/s); of all %d: %d from stand spots, %d '
                     'walking  [%s]' % (bn['placed'], bn['repaired'], fmt_dur(bn['seconds']),
                                        fmt_rate(bn['blocks_per_second']), bn['placed'] + bn['repaired'],
                                        bn['from_stand_spots'], bn['walking'], bn['source']))
        started = ('%d started (repairs included), ' % bn['lane_runs_started']) if bn['lane_runs_started'] is not None \
            else ''
        lines.append('          lane runs: %s%d ended in the log: %d walked, %d walled (all from stand spots; %d walled '
                     'starts repeated after a stop); %d cells passed out of reach by the walk'
                     % (started, t.lane_runs, t.lane_runs - t.lane_runs_walled, t.lane_runs_walled,
                        t.lane_runs_restarted, t.passed_out_of_reach))
        lines.append('          restocks %d: %d loads, %d items (%s per load); %s loads per lane (%d lanes); %s placed '
                     'per load' % (bn['restocks'], bn['loads'], bn['withdrawn'], fmt_rate(bn['withdrawn_per_load'], 0),
                                   fmt_rate(bn['loads_per_lane']), t.lanes_total, fmt_rate(bn['placed_per_load'], 0)))
        lines.append('          a load plan covers %s lanes + %s cells on average; making room %d, crowded load %d'
                     % (fmt_rate((t.covers_lanes / t.loads_build) if t.loads_build else None, 1),
                        fmt_rate((t.covers_cells / t.loads_build) if t.loads_build else None, 0), t.making_room,
                        t.crowded))
        if t.summary and t.summary['partial']:
            s = t.summary
            lines.append('          plugin tile summary counted from mid-tile only (not used): placed %d, setbacks %d'
                         % (s['placed'], s['setbacks']))
    if t.verify_passes:
        lines.append('  VERIFY  %d passes: %d wrong, %d missing; %d placed, %d broken; %s'
                     % (t.verify_passes, t.verify_wrong, t.verify_missing, bn['repaired'], t.verify_broken,
                        fmt_dur(t.bucket_total('VERIFY'))))
    broken = t.nuke_broken()
    nuke_secs = t.bucket_total('NUKE')
    if broken is not None or nuke_secs:
        if broken is None:
            lines.append('  NUKE    %s (no "nuking N blocks" line)' % fmt_dur(nuke_secs))
        else:
            rate = (broken / nuke_secs) if nuke_secs else None
            lines.append('  NUKE    %d of %d broken%s in %s (%s blocks/s), %d rounds'
                         % (broken, t.nuke_first, '' if t.nuke_done else ' so far (estimate)', fmt_dur(nuke_secs),
                            fmt_rate(rate), t.nuke_rounds))
    lines.append('  events')
    lines.append(events_line(t.events, '    ', bn['setbacks']))
    if t.map_done:
        cyc = t.cycle_seconds()
        ext = t.external_seconds()
        n, rc, rext = roll
        note = '' if t.end else ' (clean-up still running: an upper bound)'
        lines.append('  maps/h  this map %s, excl. external waits %s%s'
                     % (fmt_rate(per_hour(1, cyc)), fmt_rate(per_hour(1, cyc - ext)), note))
        lines.append('          rolling, last %d of up to %d maps: %s, excl. external waits %s'
                     % (n, last_n, fmt_rate(per_hour(n, rc)), fmt_rate(per_hour(n, rc - rext))))
    return lines


def build_report(an, files, last_n, job_filter):
    done = [t for t in an.tiles if t.map_done is not None]
    done.sort(key=lambda t: t.map_done)
    rolls = {id(t): rolling(done, i, last_n) for i, t in enumerate(done)}
    selected = [t for t in an.tiles if not job_filter or job_filter in t.job or job_filter in t.name]

    out = []
    out.append('Map art metrics from %d log file(s), %s to %s (%s wall)'
               % (len(files), fmt_ts(an.first_ts), fmt_ts(an.end_ts),
                  fmt_dur((an.end_ts - an.first_ts).total_seconds()) if an.first_ts else '-'))
    if an.builds:
        out.append('Plugin loads: %d (builds %s)' % (len(an.builds), ', '.join(sorted({b[2] for b in an.builds}))))
    out.append('')
    for t in selected:
        out.extend(render_tile(t, rolls.get(id(t), (0, 0, 0)), last_n))
        out.append('')

    # session
    busy = sum(t.cycle_seconds() for t in an.tiles)
    busy_ext = sum(t.external_seconds() for t in an.tiles)
    wall = int((an.end_ts - an.first_ts).total_seconds()) if an.first_ts else 0
    out.append('Session: %d tiles started, %d maps done, %d cycles finished'
               % (len(an.tiles), len(done), sum(1 for t in an.tiles if t.end)))
    out.append('  time in tiles %s (external waits %s), no tile %s, wall %s'
               % (fmt_dur(busy), fmt_dur(busy_ext), fmt_dur(an.session.get('NO_TILE', 0)), fmt_dur(wall)))
    out.append('  maps/h  session %s (excl. external waits %s; over wall time %s)'
               % (fmt_rate(per_hour(len(done), busy)), fmt_rate(per_hour(len(done), busy - busy_ext)),
                  fmt_rate(per_hour(len(done), wall))))
    if done:
        n, rc, rext = rolling(done, len(done) - 1, last_n)
        maps = [t.map_seconds() for t in done[-n:]]
        out.append('  last %d maps: maps/h %s (excl. external waits %s); mean map time %s, mean cycle %s'
                   % (n, fmt_rate(per_hour(n, rc)), fmt_rate(per_hour(n, rc - rext)),
                      fmt_dur(sum(maps) / len(maps)), fmt_dur(rc / n)))
    total = sum(an.session.values())
    out.extend(render_table(bucket_rows(an.session, {}, False), total, False))
    out.append('  events')
    out.append(events_line(an.events, '    '))
    return '\n'.join(out) + '\n'


def build_json(an, files, last_n, job_filter):
    done = sorted([t for t in an.tiles if t.map_done is not None], key=lambda t: t.map_done)
    rolls = {}
    for i, t in enumerate(done):
        n, rc, rext = rolling(done, i, last_n)
        rolls[id(t)] = {'maps': n, 'cycle_seconds': rc, 'external_seconds': rext,
                        'maps_per_hour': per_hour(n, rc), 'maps_per_hour_excl_external': per_hour(n, rc - rext)}
    selected = [t for t in an.tiles if not job_filter or job_filter in t.job or job_filter in t.name]
    busy = sum(t.cycle_seconds() for t in an.tiles)
    busy_ext = sum(t.external_seconds() for t in an.tiles)
    wall = int((an.end_ts - an.first_ts).total_seconds()) if an.first_ts else 0
    return {
        'files': [{'name': f[0], 'first': f[1].isoformat() if f[1] else None,
                   'last': f[2].isoformat() if f[2] else None, 'lines': f[3]} for f in files],
        'first': an.first_ts.isoformat() if an.first_ts else None,
        'last': an.end_ts.isoformat() if an.end_ts else None,
        'plugin_loads': [{'at': b[0].isoformat(), 'version': b[1], 'build': b[2]} for b in an.builds],
        'tiles': [tile_dict(t, rolls.get(id(t))) for t in selected],
        'session': {
            'tiles_started': len(an.tiles), 'maps_done': len(done),
            'cycles_finished': sum(1 for t in an.tiles if t.end),
            'seconds_in_tiles': busy, 'external_seconds': busy_ext, 'wall_seconds': wall,
            'maps_per_hour': per_hour(len(done), busy),
            'maps_per_hour_excl_external': per_hour(len(done), busy - busy_ext),
            'maps_per_hour_wall': per_hour(len(done), wall),
            'last_n': last_n,
            'rolling': rolls.get(id(done[-1])) if done else None,
            'buckets': {b: an.session[b] for b in BUCKET_ORDER if an.session.get(b)},
            'events': dict(an.events.as_dict(), setbacks_min=an.events.setbacks_min()),
        },
    }


def analyze(paths):
    lines, files = parse_files(expand_paths(paths))
    return Analyzer(lines).run(), files


def main(argv=None):
    ap = argparse.ArgumentParser(description='Maps-per-hour metrics from the map art plugin log.')
    ap.add_argument('logs', nargs='+', help='latest.log, rotated logs (.log, .log.zip, .log.gz) or a log directory')
    ap.add_argument('--last', type=int, default=5, help='rolling window in maps (default 5)')
    ap.add_argument('--job', default=None, help='print only tiles whose job id or name contains this')
    ap.add_argument('--json', action='store_true', help='JSON output')
    a = ap.parse_args(argv)
    if a.last < 1:
        ap.error('--last must be at least 1')
    an, files = analyze(a.logs)
    if not an.lines:
        print('no timestamped log lines in %d file(s)' % len(files), file=sys.stderr)
        return 1
    if a.json:
        json.dump(build_json(an, files, a.last, a.job), sys.stdout, indent=2)
        sys.stdout.write('\n')
    else:
        sys.stdout.write(build_report(an, files, a.last, a.job))
    return 0


if __name__ == '__main__':
    sys.exit(main())
