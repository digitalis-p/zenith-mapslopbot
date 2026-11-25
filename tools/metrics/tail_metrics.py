#!/usr/bin/env python3
"""The tail between maps, from the map art plugin's ZenithProxy log. Python 3 stdlib only; run with -I.

Usage:
  python3 -I tools/metrics/tail_metrics.py [--json] LOG [LOG ...]

  LOG     as for map_metrics.py: latest.log, a rotated proxy-DATE.N.log, the same zipped (.zip, every member) or
          gzipped (.gz), or a directory (its latest.log and proxy-*.log[.zip|.gz]; launcher.log and debug.log are left
          out). The lines are merged by their timestamps and lines repeated in two files are read once.
  --json  machine-readable output instead of the text report.

The tail of map N runs from its "#job name [x,z] stored in" line (the plugin's "Map done" follows in the same second)
to the first "phase X -> BUILD" of another map after it: the clean-up of N, the choice of N+1 and its first restock.
Without such a BUILD in the logs the tail ends where N's FINISH_TILE ends ("no next map"; "clean-up running" when the
logs end first); those are reported but left out of the summary. It never prints log text, so no coordinates reach
its output: numbers, phase names and map labels only.

Time accounting is map_metrics.py's (imported from this directory: reading, merging, phases, restarts, disconnects,
player control, pauses, materials waits): every second between two log lines goes to exactly one bucket, decided by
the state after the first of them. Split or added here:
  nuke: breaking        NUKE, outside the two below
  nuke: mend stop       from a mend line in NUKE ("no room for XP bottles", "fetching N XP bottles", "mending ... with
                        N XP bottles", "breaking paused (...)") until "nuking N blocks", "Breaking N blocks", "Break
                        round", a retry ("[NUKE] ... (attempt n/m)"), "leaving the art layer" or a phase change
  nuke: exit walk       from "leaving the art layer (or clear sweep) of P after the nuke" until the phase changes; a
                        "nuking", "Breaking", "Break round" or retry line in between goes back to breaking (a re-nuke)
  clear: machines       WAIT_CLEAR until "platform P clear N s after the clear was pressed" (again after each
                        CLEAR -> WAIT_CLEAR)
  clear: run-on         the rest of WAIT_CLEAR: the carts' run-on (also after a restart that resumes in it)
  SELECT+INSPECT        SELECT and INSPECT, also SELECT before a map is selected
  materials: re-checks  SELECT, INSPECT and STOCK_CHECK of a map that went back to the queue short of materials and
                        has not built since (the passes that look again while its materials wait lasts)
  idle: no tile         IDLE with no map selected or waiting for materials (the queue is empty: between jobs)
External (left out of "excl. external"): offline: restart, offline: disconnected, player control, paused, materials
wait, materials: re-checks, idle: no tile (and the FILL_MAP cover/eyeblossom waits, which a tail never has).

Also per tail: NUKE blocks (the first "nuking N blocks") and rounds, mend stops and exit walks; XP mend lines ("mending
... with N XP bottles": lines and bottles) in NUKE, during the clear ("mending ... during the clear") and elsewhere,
and the mends that waited for the clear ("TOOL at U/M: its mend waits for the clear"); clear presses, the first
"clear N s" value (every one kept in --json), the sweep's farthest chunk against the simulation distance; the run-on
from the "collection: ..." line; STOCK_CHECK rescans ("short of [...]; rescanning N") and scans ("scanning N material
containers"); restock loads and sweep size; failed drops ("drop at ... failed") and "could not get rid of" warnings.
Summary: the mean over the complete tails, per bucket, excl. external.
"""

import argparse
import bisect
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)  # python3 -I leaves the script's own directory off sys.path
import map_metrics as mm  # noqa: E402

# ---------------------------------------------------------------------------------------------------------------
# Line patterns (BuildEngine, BreakBlocksTask/NukeRun, CollectionWait and ClearPresence log calls)

R_MEND_STOP = re.compile(r'^(no room for XP bottles|fetching \d+ XP bottles|mending .+ with \d+ XP bottles|'
                         r'breaking paused \()')
R_BREAKING = re.compile(r'^(nuking \d+ blocks|Breaking \d+ blocks|Break round |\[NUKE\] .* \(attempt \d+/\d+\)$)')
R_LEAVING = re.compile(r'^leaving the (?:art layer|clear sweep) of \S+ after the nuke')
R_MEND_CLEAR = re.compile(r'^mending .+ during the clear with (\d+) XP bottles')
R_MEND = re.compile(r'^mending .+ with (\d+) XP bottles')
R_MEND_WAITS = re.compile(r'^\S.*: its mend waits for the clear( \([^)]*\))?: ')
R_CLEAR_PRESS = re.compile(r'^clear on \S+ \(attempt (\d+)\): going to press')
R_PRESENCE = re.compile(r'^clear on \S+: the farthest chunk of the sweep is (\d+) chunks from where the bot waits; '
                        r'server simulation distance (?:(\d+) chunks|unknown)')
R_CLEAR_READ = re.compile(r'^platform \S+ clear (\d+(?:\.\d+)?) s after the clear was pressed')
R_COLLECTION = re.compile(r'^collection: (?:0 items left after ([\d.]+) s; carts off after ([\d.]+) s'
                          r'|cap reached with (\d+) items? left|(cap reached, items left unknown)'
                          r'|fixed run-on of (\d+) s over)')
R_RESCAN = re.compile(r'^short of \[.*\]; rescanning (\d+) empty or matching material containers')
R_SCANNING = re.compile(r'^scanning (\d+) material containers')
R_SCAN_SWEEP = re.compile(r'^scan sweep: (\d+) containers from (\d+) stand spots')
R_RESTOCK_LOAD = re.compile(r'^restock: \d+ lanes left, ')
R_RESTOCK_SWEEP = re.compile(r'^restock sweep: (\d+) containers from (\d+) stand spots')
R_DROP_FAILED = re.compile(r'^drop at .* failed: ')
R_NOT_RID = re.compile(r'^could not get rid of ')

# tail buckets in display order; the core ones are always shown
CORE = [('COUNT', 'COUNT'), ('NUKE_BREAK', 'nuke: breaking'), ('NUKE_MEND', 'nuke: mend stop'),
        ('NUKE_EXIT', 'nuke: exit walk'), ('CLEAR', 'CLEAR'), ('CLEAR_MACHINES', 'clear: machines'),
        ('CLEAR_RUNON', 'clear: run-on'), ('FINISH_TILE', 'FINISH_TILE'), ('SELECT', 'SELECT+INSPECT'),
        ('STOCK_CHECK', 'STOCK_CHECK'), ('GEAR', 'GEAR'), ('RESTOCK', 'RESTOCK')]
CORE_KEYS = [k for k, _ in CORE]
OTHER = ['STORE', 'BLOCKED', 'SAFETY_STOP', 'REQUEUED', 'IDLE', 'UNKNOWN']
EXTERNAL = ['RESTART', 'OFFLINE', 'CONTROL', 'PAUSED', 'MATERIALS_WAIT', 'MATERIALS_RECHECK', 'NO_TILE', 'COVER_WAIT',
            'BLOSSOM_WAIT']
LABEL = dict(mm.BUCKET_LABEL, MATERIALS_RECHECK='materials: re-checks', NO_TILE='idle: no tile', **dict(CORE))


def order_key(b):
    keys = CORE_KEYS + OTHER
    if b in keys:
        return 0, keys.index(b), b
    if b in EXTERNAL:
        return 2, EXTERNAL.index(b), b
    return 1, 0, b  # another phase (should not happen in a tail)


# ---------------------------------------------------------------------------------------------------------------
# Model


class TailAnalyzer(mm.Analyzer):
    """map_metrics' Analyzer that also keeps every interval with its tail bucket, and the lines a tail reports."""

    def __init__(self, lines):
        super().__init__(lines)
        self.index = {id(ln): i for i, ln in enumerate(lines)}
        self.spans = []         # (t0, t1, bucket, or the offline Episode whose end decides it)
        self.marks = []         # (line index, kind, value), in line order
        self.build_starts = []  # (line index, tile) of each "phase X -> BUILD"
        self.done_at = {}       # id(tile) -> line index of its "stored in" (Map done)
        self.end_at = {}        # id(tile) -> line index of its "phase FINISH_TILE -> ..."
        self.short = set()      # id(tile) of maps sent back short of materials that have not built since
        self.nuke_part = None   # in NUKE: None (breaking), 'mend' or 'exit'
        self.clear_read = False  # in WAIT_CLEAR: the art layer read all air
        self._span = None

    # -- accounting: the base decides the bucket, the tail refines it ---------------------------------------------

    def account(self, t0, t1):
        if int((t1 - t0).total_seconds()) <= 0:
            return
        self._span = (t0, t1)
        super().account(t0, t1)
        if self._span is not None:  # offline: restart or disconnected is known when the episode ends
            self.spans.append((t0, t1, self.episode))
            self._span = None

    def _add(self, tile, after, bucket, secs):
        super()._add(tile, after, bucket, secs)
        if self._span is not None:  # called from account() (not for the offline time settled later)
            t0, t1 = self._span
            self._span = None
            self.spans.append((t0, t1, self.tail_bucket(bucket)))

    def tail_bucket(self, bucket):
        if bucket == 'NUKE':
            return {'mend': 'NUKE_MEND', 'exit': 'NUKE_EXIT'}.get(self.nuke_part, 'NUKE_BREAK')
        if bucket == 'WAIT_CLEAR':
            return 'CLEAR_RUNON' if self.clear_read else 'CLEAR_MACHINES'
        if bucket in ('SELECT', 'INSPECT', 'STOCK_CHECK'):
            if self.tile is not None and id(self.tile) in self.short:
                return 'MATERIALS_RECHECK'
            return 'STOCK_CHECK' if bucket == 'STOCK_CHECK' else 'SELECT'
        if bucket == 'NO_TILE' and self.phase in ('SELECT', 'INSPECT', 'STOCK_CHECK'):
            return 'SELECT'  # choosing the next map
        return bucket

    def resolved_spans(self):
        return [(t0, t1, ('RESTART' if b.restart else 'OFFLINE') if isinstance(b, mm.Episode) else b)
                for t0, t1, b in self.spans]

    # -- lines ---------------------------------------------------------------------------------------------------

    def mark(self, i, kind, value=1):
        self.marks.append((i, kind, value))

    def on_plugin(self, ln):
        before = self.tile
        super().on_plugin(ln)
        self.watch(ln.msg, self.index[id(ln)], before)

    def watch(self, msg, i, before):
        """Sub-states and tail details of one plugin line, after the base handled it (self.phase is the new one)."""
        m = mm.R_PHASE.match(msg)
        if m:
            frm, to = m.group(1), m.group(2)
            self.nuke_part = None
            if to == 'WAIT_CLEAR':
                self.clear_read = False
            if before is not None and frm == 'FINISH_TILE':
                self.end_at.setdefault(id(before), i)
            if before is not None and self.tile is None and self.suspended is before \
                    and self.suspend_reason == 'materials':
                self.short.add(id(before))
            if to == 'BUILD' and self.tile is not None:
                self.short.discard(id(self.tile))
                self.build_starts.append((i, self.tile))
            return
        m = mm.R_STORED.match(msg)
        if m:
            t = self.tile_for(m.group(1), None, create=False) or self.tile
            if t is not None and t.map_done is not None and id(t) not in self.done_at:
                self.done_at[id(t)] = i
            return
        if mm.R_RESUMING.match(msg):
            self.nuke_part = None
            return
        if self.phase == 'NUKE':
            if R_LEAVING.match(msg):
                self.nuke_part = 'exit'
                self.mark(i, 'exit_walk')
                return
            if R_BREAKING.match(msg):
                self.nuke_part = None
            elif R_MEND_STOP.match(msg):
                if self.nuke_part != 'mend':
                    self.mark(i, 'mend_stop')
                self.nuke_part = 'mend'
        m = mm.R_NUKING.match(msg)
        if m:
            self.mark(i, 'nuking', int(m.group(1)))
            return
        m = R_MEND_CLEAR.match(msg)
        if m:
            self.mark(i, 'mend_clear', int(m.group(1)))
            return
        m = R_MEND.match(msg)
        if m:
            self.mark(i, 'mend_nuke' if self.phase == 'NUKE' else 'mend_other', int(m.group(1)))
            return
        if R_MEND_WAITS.match(msg):
            self.mark(i, 'mend_waits')
            return
        if R_CLEAR_PRESS.match(msg):
            self.mark(i, 'clear_press')
            return
        m = R_PRESENCE.match(msg)
        if m:
            self.mark(i, 'presence', (int(m.group(1)), int(m.group(2)) if m.group(2) else None))
            return
        m = R_CLEAR_READ.match(msg)
        if m:
            self.clear_read = True
            self.mark(i, 'clear_read', float(m.group(1)))
            return
        m = R_COLLECTION.match(msg)
        if m:
            if m.group(1):
                c = {'items_gone_after': float(m.group(1)), 'carts_off_after': float(m.group(2))}
            elif m.group(3):
                c = {'cap_reached': True, 'items_left': int(m.group(3))}
            elif m.group(4):
                c = {'cap_reached': True, 'items_left': None}
            else:
                c = {'fixed_run_on': int(m.group(5))}
            self.mark(i, 'collection', c)
            return
        for kind, rx in (('rescan', R_RESCAN), ('scan', R_SCANNING), ('scan_sweep', R_SCAN_SWEEP),
                         ('restock_sweep', R_RESTOCK_SWEEP)):
            m = rx.match(msg)
            if m:
                self.mark(i, kind, tuple(int(g) for g in m.groups()))
                return
        for kind, rx in (('restock', R_RESTOCK_LOAD), ('drop_failed', R_DROP_FAILED), ('not_rid', R_NOT_RID)):
            if rx.match(msg):
                self.mark(i, kind)
                return


# ---------------------------------------------------------------------------------------------------------------
# Tails


def details(marks):
    """The numbers a tail reports, from its marks [(index, kind, value)]."""
    by = {}
    for _, kind, value in marks:
        by.setdefault(kind, []).append(value)

    def n(kind):
        return len(by.get(kind, []))

    def total(kind, field=None):
        return sum(v if field is None else v[field] for v in by.get(kind, []))

    presence = by.get('presence', [None])[-1]
    reads = by.get('clear_read', [])
    return {
        'nuke': {'blocks': by.get('nuking', [None])[0], 'rounds': n('nuking'), 'mend_stops': n('mend_stop'),
                 'exit_walks': n('exit_walk')},
        'mends': {'in_nuke': {'lines': n('mend_nuke'), 'bottles': total('mend_nuke')},
                  'during_clear': {'lines': n('mend_clear'), 'bottles': total('mend_clear')},
                  'elsewhere': {'lines': n('mend_other'), 'bottles': total('mend_other')},
                  'waited_for_clear': n('mend_waits')},
        'clear': {'presses': n('clear_press'), 'clear_seconds': reads[0] if reads else None, 'clear_reads': reads,
                  'farthest_chunk': presence[0] if presence else None,
                  'simulation_distance': presence[1] if presence else None},
        'run_on': by.get('collection', []),
        'stock_check': {'rescans': n('rescan'), 'rescan_containers': total('rescan', 0), 'scans': n('scan'),
                        'scanned_containers': total('scan', 0), 'scan_stand_spots': total('scan_sweep', 1)},
        'restock': {'loads': n('restock'), 'sweep_containers': total('restock_sweep', 0),
                    'sweep_stand_spots': total('restock_sweep', 1), 'failed_drops': n('drop_failed'),
                    'could_not_get_rid': n('not_rid')},
    }


def transitions(an):
    """One dict per map done, in order: its tail, buckets and details."""
    spans = an.resolved_spans()
    span_starts = [s[0] for s in spans]
    mark_index = [mk[0] for mk in an.marks]
    done = sorted((t for t in an.tiles if id(t) in an.done_at), key=lambda t: an.done_at[id(t)])
    out = []
    for t in done:
        i0 = an.done_at[id(t)]
        nxt = next(((i, nt) for i, nt in an.build_starts if i > i0 and nt is not t), None)
        if nxt is not None:
            i1, nt = nxt
            end, state = an.lines[i1].ts, 'complete'
        elif id(t) in an.end_at:
            i1, nt = an.end_at[id(t)], None
            end, state = an.lines[i1].ts, 'no next map'
        else:
            i1, nt = len(an.lines), None
            end, state = an.end_ts, 'clean-up running'
        start = t.map_done
        buckets = {k: 0 for k, _ in CORE}
        k = bisect.bisect_left(span_starts, start)
        while k < len(spans) and spans[k][1] <= end:
            t0, t1, b = spans[k]
            buckets[b] = buckets.get(b, 0) + int((t1 - t0).total_seconds())
            k += 1
        buckets = {b: buckets[b] for b in sorted(buckets, key=order_key)}
        total = int((end - start).total_seconds())
        ext = sum(v for b, v in buckets.items() if b in EXTERNAL)
        a, z = bisect.bisect_left(mark_index, i0), bisect.bisect_left(mark_index, i1)
        d = {'from': t.label(), 'to': nt.label() if nt else None, 'next_job': bool(nt and nt.job != t.job),
             'state': state, 'start': start.isoformat(), 'end': end.isoformat(), 'seconds': total,
             'external_seconds': ext, 'seconds_excl_external': total - ext, 'buckets': buckets}
        d.update(details(an.marks[a:z]))
        out.append(d)
    return out


def summary(trs):
    done = [tr for tr in trs if tr['state'] == 'complete']
    n = len(done)
    if not n:
        return {'transitions': 0, 'left_out': len(trs)}
    keys = sorted({b for tr in done for b in tr['buckets']}, key=order_key)
    mean = {b: sum(tr['buckets'].get(b, 0) for tr in done) / n for b in keys}
    return {'transitions': n, 'left_out': len(trs) - n,
            'mean_seconds': sum(tr['seconds'] for tr in done) / n,
            'mean_external_seconds': sum(tr['external_seconds'] for tr in done) / n,
            'mean_seconds_excl_external': sum(tr['seconds_excl_external'] for tr in done) / n,
            'buckets_mean': {b: v for b, v in mean.items() if b not in EXTERNAL},
            'external_buckets_mean': {b: v for b, v in mean.items() if b in EXTERNAL and v}}


# ---------------------------------------------------------------------------------------------------------------
# Report

fmt_dur = mm.fmt_dur
ROW = '  %-28s %9s %8s %6s'  # label, h:mm:ss, seconds, share


def fmt_ts(iso):
    return iso.replace('-', '/').replace('T', ' ')


def share(v, total):
    return '%5.1f%%' % (100.0 * v / total) if total else '     -'


def count(n, word, many=None):
    return '%d %s' % (n, word if n == 1 else (many or word + 's'))


def run_on_text(c):
    if 'items_gone_after' in c:
        return '0 items left after %.1f s, carts off after %.1f s' % (c['items_gone_after'], c['carts_off_after'])
    if 'fixed_run_on' in c:
        return 'fixed run-on of %d s' % c['fixed_run_on']
    if c.get('items_left') is not None:
        return 'cap reached with %d items left' % c['items_left']
    return 'cap reached, items left unknown'


def render(tr):
    out = ['%s -> %s%s' % (tr['from'], tr['to'] or '(no next map)', '  (next job)' if tr['next_job'] else '')]
    if tr['state'] == 'complete':
        out.append('  map done %s, first BUILD of the next map %s' % (fmt_ts(tr['start']), fmt_ts(tr['end'])))
    elif tr['state'] == 'no next map':
        out.append('  map done %s, FINISH_TILE ended %s; no BUILD of another map after it in the logs'
                   % (fmt_ts(tr['start']), fmt_ts(tr['end'])))
    else:
        out.append('  map done %s; clean-up still running when the logs end at %s'
                   % (fmt_ts(tr['start']), fmt_ts(tr['end'])))
    total = tr['seconds']
    out.append('  tail %s (%d s), excl. external waits %s (%d s); external %s'
               % (fmt_dur(total), total, fmt_dur(tr['seconds_excl_external']), tr['seconds_excl_external'],
                  fmt_dur(tr['external_seconds'])))
    out.append(ROW % ('time', 'h:mm:ss', 'secs', 'share'))
    for b, v in tr['buckets'].items():
        if b not in EXTERNAL and (v or b in CORE_KEYS):
            out.append(ROW % (LABEL.get(b, b), fmt_dur(v), '%d' % v, share(v, total)))
    out.append(ROW % ('external waits', fmt_dur(tr['external_seconds']), '%d' % tr['external_seconds'],
                      share(tr['external_seconds'], total)))
    for b, v in tr['buckets'].items():
        if b in EXTERNAL and v:
            out.append(ROW % ('  ' + LABEL.get(b, b), fmt_dur(v), '%d' % v, share(v, total)))
    nk, mends, cl, sc, rs = tr['nuke'], tr['mends'], tr['clear'], tr['stock_check'], tr['restock']
    out.append('  NUKE     %s; %s, %s'
               % ('nothing to break (no "nuking N blocks" line)' if nk['blocks'] is None
                  else '%d blocks, %s' % (nk['blocks'], count(nk['rounds'], 'round')),
                  count(nk['mend_stops'], 'mend stop'), count(nk['exit_walks'], 'exit walk')))

    def mend(part):
        return '%s (%d bottles)' % (count(mends[part]['lines'], 'mend'), mends[part]['bottles'])

    out.append('  mends    in NUKE %s; during the clear %s, %d waited for the clear; elsewhere %s'
               % (mend('in_nuke'), mend('during_clear'), mends['waited_for_clear'], mend('elsewhere')))
    clear = '%s; clear %s' % (count(cl['presses'], 'press', 'presses'), '-' if cl['clear_seconds'] is None
                              else '%.1f s after the press' % cl['clear_seconds'])
    if len(cl['clear_reads']) > 1:
        clear += ' (read %d times: the first counts)' % len(cl['clear_reads'])
    if cl['farthest_chunk'] is not None:
        clear += '; farthest sweep chunk %d, simulation distance %s' % (
            cl['farthest_chunk'], '-' if cl['simulation_distance'] is None else cl['simulation_distance'])
    out.append('  CLEAR    ' + clear)
    out.append('  run-on   ' + ('; '.join(run_on_text(c) for c in tr['run_on']) or '-'))
    out.append('  STOCK    %s (%d containers); %s (%d containers from %d stand spots)'
               % (count(sc['rescans'], 'rescan'), sc['rescan_containers'], count(sc['scans'], 'scan'),
                  sc['scanned_containers'], sc['scan_stand_spots']))
    out.append('  RESTOCK  %s, sweep %d containers from %d stand spots; %s, %d "could not get rid of"'
               % (count(rs['loads'], 'load'), rs['sweep_containers'], rs['sweep_stand_spots'],
                  count(rs['failed_drops'], 'failed drop'), rs['could_not_get_rid']))
    return out


def build_report(an, files, trs):
    out = ['Tails between maps from %d log file(s), %s to %s'
           % (len(files), mm.fmt_ts(an.first_ts), mm.fmt_ts(an.end_ts)), '']
    for tr in trs:
        out.extend(render(tr))
        out.append('')
    s = summary(trs)
    if not s['transitions']:
        out.append('Summary: no complete tail (%d without a next map)' % s['left_out'])
        return '\n'.join(out) + '\n'
    excl = s['mean_seconds_excl_external']
    out.append('Summary: mean over %d complete tails (%d without a next map left out)' % (s['transitions'],
                                                                                         s['left_out']))
    out.append('  mean tail %s (%.1f s), excl. external waits %s (%.1f s); external %.1f s'
               % (fmt_dur(s['mean_seconds']), s['mean_seconds'], fmt_dur(excl), excl, s['mean_external_seconds']))
    out.append(ROW % ('mean (share excl. external)', 'h:mm:ss', 'secs', 'share'))
    for b, v in s['buckets_mean'].items():
        out.append(ROW % (LABEL.get(b, b), fmt_dur(v), '%.1f' % v, share(v, excl)))
    ext = s['mean_external_seconds']
    out.append((ROW % ('external waits', fmt_dur(ext), '%.1f' % ext, '')).rstrip())
    for b, v in s['external_buckets_mean'].items():
        out.append((ROW % ('  ' + LABEL.get(b, b), fmt_dur(v), '%.1f' % v, '')).rstrip())
    return '\n'.join(out) + '\n'


def build_json(an, files, trs):
    return {
        'files': [{'name': f[0], 'first': f[1].isoformat() if f[1] else None,
                   'last': f[2].isoformat() if f[2] else None, 'lines': f[3]} for f in files],
        'first': an.first_ts.isoformat() if an.first_ts else None,
        'last': an.end_ts.isoformat() if an.end_ts else None,
        'external_buckets': EXTERNAL,
        'transitions': trs,
        'summary': summary(trs),
    }


def analyze(paths):
    lines, files = mm.parse_files(mm.expand_paths(paths))
    an = TailAnalyzer(lines).run()
    return an, files, transitions(an)


def main(argv=None):
    ap = argparse.ArgumentParser(description='The tail between maps (Map done -> next BUILD) from the plugin log.')
    ap.add_argument('logs', nargs='+', help='latest.log, rotated logs (.log, .log.zip, .log.gz) or a log directory')
    ap.add_argument('--json', action='store_true', help='JSON output')
    a = ap.parse_args(argv)
    an, files, trs = analyze(a.logs)
    if not an.lines:
        print('no timestamped log lines in %d file(s)' % len(files), file=sys.stderr)
        return 1
    if a.json:
        json.dump(build_json(an, files, trs), sys.stdout, indent=2)
        sys.stdout.write('\n')
    else:
        sys.stdout.write(build_report(an, files, trs))
    return 0


if __name__ == '__main__':
    sys.exit(main())
