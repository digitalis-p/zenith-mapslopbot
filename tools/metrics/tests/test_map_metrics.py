#!/usr/bin/env python3
"""Tests for tools/metrics/map_metrics.py. Python stdlib only.

Run: python3 -I tools/metrics/tests/test_map_metrics.py   (or: python3 -I -m unittest discover -s tools/metrics/tests)

The fixtures in fixtures/ are synthetic plugin logs in the ZenithProxy latest.log format: made-up jobs, small
made-up coordinates, nothing from a real log. Expected times are worked out by hand from their timestamps.
"""
import contextlib
import gzip
import importlib.util
import io
import json
import os
import re
import shutil
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, 'fixtures')
TOOL = os.path.join(os.path.dirname(HERE), 'map_metrics.py')

spec = importlib.util.spec_from_file_location('map_metrics', TOOL)
mm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mm)


def fixture(name):
    return os.path.join(FIX, name)


def analyze(*paths):
    an, files = mm.analyze(list(paths))
    return an, files


def tiles_by_label(an):
    return {t.label(): t for t in an.tiles}


def run_main(args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = mm.main(args)
    return rc, out.getvalue()


class FullCycleTest(unittest.TestCase):
    """Two maps of one job, start to finish: every phase, BUILD/VERIFY/NUKE detail, a back-off with an escape."""

    @classmethod
    def setUpClass(cls):
        cls.an, _ = analyze(fixture('full_cycle.log'))
        cls.tiles = tiles_by_label(cls.an)
        cls.t1 = cls.tiles['#j0007 testart [0,0]']
        cls.t2 = cls.tiles['#j0007 testart [1,0]']

    def test_tiles(self):
        self.assertEqual(len(self.an.tiles), 2)
        self.assertEqual((self.t1.map_index, self.t1.map_count, self.t1.blocks, self.t1.platform), (1, 2, 1000, 'moss'))
        self.assertFalse(self.t1.partial_start)

    def test_phase_times_first_map(self):
        t = self.t1
        self.assertEqual(t.before, {'INSPECT': 5, 'STOCK_CHECK': 60, 'GEAR': 10, 'RESTOCK': 105, 'BUILD': 360,
                                    'VERIFY': 30, 'FILL_MAP': 10, 'CHECK_MAP': 1, 'LOCK': 19, 'RENAME': 10,
                                    'STORE': 5})
        self.assertEqual(t.after, {'COUNT': 5, 'NUKE': 45, 'CLEAR': 25, 'WAIT_CLEAR': 60, 'FINISH_TILE': 10})
        self.assertEqual(t.map_seconds(), 615)
        self.assertEqual(t.cycle_seconds(), 760)
        self.assertEqual(t.external_seconds(), 0)
        self.assertEqual(mm.fmt_ts(t.map_done), '2026/01/02 10:11:15')
        self.assertEqual(mm.fmt_ts(t.end), '2026/01/02 10:13:40')

    def test_phase_times_second_map(self):
        t = self.t2
        self.assertEqual(t.map_seconds(), 365)
        self.assertEqual(t.cycle_seconds(), 460)
        self.assertEqual(t.before['BUILD'], 240)
        self.assertEqual(t.after, {'NUKE': 5, 'CLEAR': 20, 'WAIT_CLEAR': 60, 'FINISH_TILE': 10})

    def test_build(self):
        t = self.t1
        self.assertEqual(t.placed, 249 + 150 + 100 + 249 + 250)  # confirmed + late clicks of the BUILD lanes
        self.assertEqual(t.placed_reported, 250 + 150 + 100 + 250 + 250)
        self.assertEqual((t.lane_runs, t.lane_runs_walled, t.lane_runs_restarted), (5, 1, 1))
        self.assertEqual((t.spot_placed, t.spot_walks, t.passed_out_of_reach), (253, 22, 2))
        self.assertEqual((t.loads_build, t.loads_repair, t.loads_empty, t.lanes_total), (3, 0, 1, 4))
        self.assertEqual(t.withdrawn, 850)
        self.assertEqual((t.covers_lanes, t.covers_cells), (4, 100))
        self.assertEqual((t.making_room, t.crowded), (1, 1))

    def test_verify_and_nuke(self):
        t = self.t1
        self.assertEqual((t.verify_passes, t.verify_wrong, t.verify_missing, t.verify_placed, t.verify_broken),
                         (1, 2, 1, 3, 2))
        self.assertEqual((t.nuke_first, t.nuke_broken(), t.nuke_done), (100, 100, True))
        self.assertEqual(t.bucket_total('NUKE'), 45)
        self.assertIsNone(self.t2.nuke_broken())  # nothing to nuke: no "nuking" line
        self.assertTrue(self.t2.nuke_done)

    def test_events(self):
        e = self.t1.events
        self.assertEqual((e.setback_notices, e.setbacks_noticed, e.setback_peak), (1, 10, 10))
        self.assertEqual((e.backoffs, e.backoff_setbacks, e.backoff_seconds), (1, 5, 5))
        self.assertEqual((e.escapes, e.escapes_done, e.escapes_failed, e.escapes_dropped), (1, 1, 0, 0))
        self.assertEqual(e.setbacks_min(), 10)
        self.assertEqual(self.t2.events.backoffs, 0)

    def test_session(self):
        an = self.an
        self.assertEqual(an.session.get('NO_TILE'), 580)  # 10 s start-up, 50 s idle, 520 s after the job
        self.assertEqual(sum(an.session.values()), 1800)
        _, out = run_main([fixture('full_cycle.log')])
        self.assertIn('maps/h  session 5.90 (excl. external waits 5.90; over wall time 4.00)', out)
        self.assertIn('rolling, last 2 of up to 5 maps: 5.90', out)
        self.assertIn('BUILD   998 placed (+3 in VERIFY) in 0:06:00 (2.77 blocks/s); of all 1001: 253 from stand '
                      'spots, 748 walking  [log lines]', out)
        self.assertIn('lane runs: 5 ended in the log: 4 walked, 1 walled', out)
        self.assertIn('restocks 3: 2 loads, 850 items (425 per load); 0.50 loads per lane (4 lanes); 499 placed per '
                      'load', out)
        self.assertIn('setbacks >= 10 (1 notices, peak 10 in 300 s; 5 in back-off bursts)', out)
        self.assertIn('NUKE    100 of 100 broken in 0:00:45 (2.22 blocks/s), 1 rounds', out)


class RestartMidMapTest(unittest.TestCase):
    """A map across four proxy runs: a materials wait, a pause kept across a restart (resumed by hand), a pause the
    start script cleared, and NUKE still running in latest.log. Logs as .zip, .gz and plain, given out of order."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        z = os.path.join(cls.tmp, 'proxy-2026-01-03.1.log.zip')
        with zipfile.ZipFile(z, 'w') as zf:
            zf.write(fixture('restart_1.log'), 'proxy-2026-01-03.1.log')
        g = os.path.join(cls.tmp, 'proxy-2026-01-03.2.log.gz')
        with open(fixture('restart_2.log'), 'rb') as src, gzip.open(g, 'wb') as dst:
            dst.write(src.read())
        latest = os.path.join(cls.tmp, 'latest.log')
        shutil.copy(fixture('restart_latest.log'), latest)
        cls.paths = [latest, z, g]
        cls.an, cls.files = analyze(*cls.paths)
        cls.t = cls.an.tiles[0]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def test_one_tile_across_restarts(self):
        self.assertEqual(len(self.an.tiles), 1)
        self.assertEqual(self.t.label(), '#j0009 sample [0,0]')
        self.assertEqual(len(self.an.builds), 4)
        self.assertEqual([b[2] for b in self.an.builds], ['aaa1111', 'bbb2222', 'ccc3333', 'ddd4444'])

    def test_phase_times(self):
        t = self.t
        self.assertEqual(t.before, {'INSPECT': 10, 'STOCK_CHECK': 65, 'MATERIALS_WAIT': 300, 'RESTOCK': 180,
                                    'BUILD': 1505, 'PAUSED': 33, 'RESTART': 287, 'FILL_MAP': 10, 'LOCK': 20,
                                    'RENAME': 10, 'STORE': 5})
        self.assertEqual(t.after, {'NUKE': 165, 'RESTART': 90})
        self.assertEqual(t.map_seconds(), 2425)
        self.assertEqual(t.cycle_seconds(), 2680)
        self.assertIsNone(t.end)
        self.assertEqual(t.external_seconds(), 300 + 33 + 287 + 90)

    def test_events(self):
        e = self.t.events
        self.assertEqual((e.disconnects, e.disconnects_manual), (3, 3))
        self.assertEqual((e.restarts, e.restart_seconds), (3, 148 + 139 + 90))
        self.assertEqual((e.reconnects, e.controls, e.pauses), (0, 0, 2))

    def test_build_and_nuke(self):
        t = self.t
        self.assertEqual(t.placed, 250 + 255 + 1490)
        self.assertEqual((t.loads_build, t.lanes_total), (3, 8))
        self.assertEqual((t.nuke_first, t.nuke_last, t.nuke_broken(), t.nuke_done), (300, 120, 200, False))

    def test_directory_and_duplicates(self):
        an, files = analyze(self.tmp, *self.paths)  # the directory and the files again: every line read once
        self.assertEqual(an.tiles[0].before, self.t.before)
        self.assertEqual(an.tiles[0].after, self.t.after)
        self.assertEqual(len(files), 6)

    def test_report_marks_running_cleanup(self):
        _, out = run_main(self.paths)
        self.assertIn('cycle end  - (clean-up running; 0:44:40 so far', out)
        self.assertIn('NUKE    200 of 300 broken so far (estimate) in 0:02:45', out)
        self.assertIn('(clean-up still running: an upper bound)', out)


class DisconnectTest(unittest.TestCase):
    """A connection reset with failed reconnect attempts, then a player taking control for a minute."""

    @classmethod
    def setUpClass(cls):
        cls.an, _ = analyze(fixture('disconnect.log'))
        cls.t = cls.an.tiles[0]

    def test_phase_times(self):
        t = self.t
        self.assertEqual(t.before, {'INSPECT': 5, 'STOCK_CHECK': 5, 'RESTOCK': 30, 'BUILD': 390, 'OFFLINE': 90,
                                    'CONTROL': 60, 'FILL_MAP': 10, 'LOCK': 10, 'RENAME': 10, 'STORE': 5})
        self.assertEqual(t.map_seconds(), 615)
        self.assertEqual(t.cycle_seconds(), 705)
        self.assertEqual(t.external_seconds(), 150)

    def test_events(self):
        e = self.t.events
        self.assertEqual((e.disconnects, e.disconnects_connection, e.reconnect_attempts_failed), (1, 1, 2))
        self.assertEqual((e.reconnects, e.reconnect_seconds), (1, 90))
        self.assertEqual((e.controls, e.control_seconds), (1, 60))
        self.assertEqual(e.restarts, 0)
        self.assertEqual(e.keepalives, 0)
        self.assertEqual(self.an.events.keepalives, 1)  # in IDLE after the job: the session's, no tile's

    def test_rates(self):
        _, out = run_main([fixture('disconnect.log')])
        self.assertIn('maps/h  this map 5.11, excl. external waits 6.49', out)


class CoverWaitTest(unittest.TestCase):
    """FILL_MAP waits for the platform above (across a reconnect, covered again once), then for eyeblossoms."""

    @classmethod
    def setUpClass(cls):
        cls.an, _ = analyze(fixture('cover_wait.log'))
        cls.t = cls.an.tiles[0]

    def test_fill_map_split(self):
        t = self.t
        self.assertEqual(t.before['COVER_WAIT'], 600 + 240 + 90)
        self.assertEqual(t.before['BLOSSOM_WAIT'], 180)
        self.assertEqual(t.before['FILL_MAP'], 30 + 10)
        self.assertEqual(t.before['OFFLINE'], 60)
        self.assertEqual(t.map_seconds(), 1525)
        self.assertEqual(t.cycle_seconds(), 1620)
        self.assertEqual(t.external_seconds(), 930 + 180 + 60)
        self.assertEqual(t.events.keepalives, 1)

    def test_report(self):
        _, out = run_main([fixture('cover_wait.log')])
        self.assertIn('FILL_MAP (incl. waits)        0:19:10', out)
        self.assertIn('  of which cover wait         0:15:30', out)
        self.assertIn('  of which eyeblossom wait    0:03:00', out)
        self.assertIn('maps/h  this map 2.22, excl. external waits 8.00', out)


class PluginSummaryTest(unittest.TestCase):
    """The plugin's "tile summary" line is preferred over the log-derived counts, unless it counted mid-tile only."""

    @classmethod
    def setUpClass(cls):
        cls.an, _ = analyze(fixture('summary.log'))
        cls.tiles = tiles_by_label(cls.an)
        cls.full = cls.tiles['#j0012 counted [0,0]']
        cls.late = cls.tiles['#j0012 counted [1,0]']

    def test_summary_parsed(self):
        self.assertEqual(self.full.summary, {'placed': 602, 'build': 600, 'repair': 2, 'from_stand_spots': 300,
                                             'walking': 302, 'lane_runs': 4, 'restocks': 1, 'loads': 1,
                                             'withdrawn': 640, 'setbacks': 9, 'partial': False})
        self.assertTrue(self.late.summary['partial'])
        self.assertIsNone(self.late.exact())

    def test_preferred_when_complete(self):
        bn = mm.build_numbers(self.full)
        self.assertEqual(bn['source'], 'plugin tile summary')
        self.assertEqual((bn['placed'], bn['repaired'], bn['walking'], bn['setbacks']), (600, 2, 302, 9))
        self.assertEqual(self.full.placed, 480, 'the log only shows the lane runs that ended')
        bl = mm.build_numbers(self.late)
        self.assertEqual((bl['source'], bl['placed'], bl['setbacks']), ('log lines', 300, None))

    def test_report(self):
        _, out = run_main([fixture('summary.log')])
        self.assertIn('BUILD   600 placed (+2 in VERIFY) in 0:04:00 (2.50 blocks/s); of all 602: 300 from stand spots, '
                      '302 walking  [plugin tile summary]', out)
        self.assertIn('lane runs: 4 started (repairs included), 2 ended in the log: 1 walked, 1 walled', out)
        self.assertIn('restocks 1: 1 loads, 640 items (640 per load); 0.50 loads per lane (2 lanes); 600 placed per load',
                      out)
        self.assertIn('setbacks 9 (plugin count; log: 0 notices, peak 0 in 300 s; 5 in back-off bursts)', out)
        self.assertIn('plugin tile summary counted from mid-tile only (not used): placed 120, setbacks 0', out)
        self.assertIn('BUILD   300 placed (+0 in VERIFY) in 0:02:00 (2.50 blocks/s)', out)
        _, js = run_main(['--json', fixture('summary.log')])
        d = json.loads(js)
        self.assertEqual(d['tiles'][0]['build']['source'], 'plugin tile summary')
        self.assertEqual(d['tiles'][0]['events']['setbacks'], 9)
        self.assertEqual(d['tiles'][1]['build']['source'], 'log lines')
        self.assertTrue(d['tiles'][1]['plugin_summary']['partial'])


class OutputTest(unittest.TestCase):
    def test_no_coordinates_in_any_output(self):
        triple = re.compile(r'-?\d+\s*,\s*-?\d+\s*,\s*-?\d+')
        for name in ('full_cycle.log', 'disconnect.log', 'cover_wait.log', 'restart_2.log', 'summary.log'):
            for extra in ([], ['--json']):
                _, out = run_main(extra + [fixture(name)])
                self.assertIsNone(triple.search(out), '%s %s: %s' % (name, extra, triple.search(out)))

    def test_json(self):
        rc, out = run_main(['--json', '--last', '1', fixture('full_cycle.log')])
        self.assertEqual(rc, 0)
        d = json.loads(out)
        self.assertEqual([t['tile'] for t in d['tiles']], [[0, 0], [1, 0]])
        self.assertEqual(d['tiles'][0]['map_seconds'], 615)
        self.assertEqual(d['tiles'][0]['cycle_seconds'], 760)
        self.assertEqual(d['tiles'][0]['buckets']['NUKE'], {'to_map_done': 0, 'after_map_done': 45})
        self.assertEqual(d['tiles'][1]['rolling']['maps'], 1)
        self.assertEqual(d['session']['maps_done'], 2)
        self.assertAlmostEqual(d['session']['maps_per_hour'], 7200 / 1220)

    def test_job_filter(self):
        _, out = run_main(['--job', 'nomatch', fixture('full_cycle.log')])
        self.assertNotIn('#j0007', out)
        self.assertIn('Session: 2 tiles started, 2 maps done', out)

    def test_empty_input(self):
        tmp = tempfile.mkdtemp()
        try:
            p = os.path.join(tmp, 'empty.log')
            open(p, 'w').close()
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                rc, _ = run_main([p])
            self.assertEqual(rc, 1)
        finally:
            shutil.rmtree(tmp)


if __name__ == '__main__':
    unittest.main()
