#!/usr/bin/env python3
"""Tests for tools/metrics/tail_metrics.py. Python stdlib only.

Run: python3 -I tools/metrics/tests/test_tail_metrics.py   (or: python3 -I -m unittest discover -s tools/metrics/tests)

The fixtures fixtures/tail_*.log are synthetic plugin logs in the ZenithProxy latest.log format: made-up jobs, small
made-up coordinates, nothing from a real log. Expected seconds are worked out by hand from their timestamps.
"""
import contextlib
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
TOOL = os.path.join(os.path.dirname(HERE), 'tail_metrics.py')

spec = importlib.util.spec_from_file_location('tail_metrics', TOOL)
tm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tm)


def fixture(name):
    return os.path.join(FIX, name)


def run_main(args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = tm.main(args)
    return rc, out.getvalue()


def nonzero(buckets):
    return {b: v for b, v in buckets.items() if v}


class MixedTailTest(unittest.TestCase):
    """Three maps of one job: a mend stop, two exit walks and a re-nuke; a carpet map (empty NUKE); the last map mends
    during the clear (a mend that waited for it) and has no next map."""

    @classmethod
    def setUpClass(cls):
        cls.an, cls.files, cls.trs = tm.analyze([fixture('tail_mixed.log')])

    def test_tails(self):
        self.assertEqual([(t['from'], t['to'], t['state']) for t in self.trs],
                         [('#j0005 demo [0,0]', '#j0005 demo [1,0]', 'complete'),
                          ('#j0005 demo [1,0]', '#j0005 demo [2,0]', 'complete'),
                          ('#j0005 demo [2,0]', None, 'no next map')])
        self.assertFalse(any(t['next_job'] for t in self.trs))

    def test_every_second_in_one_bucket(self):
        for t in self.trs:
            self.assertEqual(sum(t['buckets'].values()), t['seconds'], t['from'])

    def test_mend_stop_exit_walk_renuke(self):
        t = self.trs[0]
        self.assertEqual((t['start'], t['end']), ('2026-02-03T10:20:30', '2026-02-03T10:35:40'))
        # NUKE 10:20:33-10:31:55 (682 s): mend stop 10:30:00-10:30:42; exit walks 10:31:00-10:31:20 (the retry line
        # ends it) and 10:31:30-10:31:55; WAIT_CLEAR 10:31:57-10:33:08-10:33:30
        self.assertEqual(t['buckets'], {'COUNT': 3, 'NUKE_BREAK': 595, 'NUKE_MEND': 42, 'NUKE_EXIT': 45, 'CLEAR': 2,
                                        'CLEAR_MACHINES': 71, 'CLEAR_RUNON': 22, 'FINISH_TILE': 22, 'SELECT': 2,
                                        'STOCK_CHECK': 36, 'GEAR': 5, 'RESTOCK': 65})
        self.assertEqual((t['seconds'], t['external_seconds'], t['seconds_excl_external']), (910, 0, 910))
        self.assertEqual(t['nuke'], {'blocks': 400, 'rounds': 3, 'mend_stops': 1, 'exit_walks': 2})
        self.assertEqual(t['mends'], {'in_nuke': {'lines': 2, 'bottles': 40},
                                      'during_clear': {'lines': 0, 'bottles': 0},
                                      'elsewhere': {'lines': 0, 'bottles': 0}, 'waited_for_clear': 0})
        self.assertEqual(t['clear'], {'presses': 1, 'clear_seconds': 71.2, 'clear_reads': [71.2], 'farthest_chunk': 3,
                                      'simulation_distance': 4})
        self.assertEqual(t['run_on'], [{'items_gone_after': 19.0, 'carts_off_after': 22.0}])
        self.assertEqual(t['stock_check'], {'rescans': 1, 'rescan_containers': 12, 'scans': 1, 'scanned_containers': 12,
                                            'scan_stand_spots': 3})
        self.assertEqual(t['restock'], {'loads': 1, 'sweep_containers': 5, 'sweep_stand_spots': 2, 'failed_drops': 1,
                                        'could_not_get_rid': 1})

    def test_carpet_map_empty_nuke(self):
        t = self.trs[1]
        self.assertEqual(nonzero(t['buckets']), {'COUNT': 3, 'NUKE_BREAK': 1, 'CLEAR': 1, 'CLEAR_MACHINES': 70,
                                                 'CLEAR_RUNON': 4, 'FINISH_TILE': 22, 'STOCK_CHECK': 1, 'RESTOCK': 43})
        self.assertEqual(t['seconds'], 145)
        self.assertEqual(t['nuke'], {'blocks': None, 'rounds': 0, 'mend_stops': 0, 'exit_walks': 0})
        self.assertEqual(t['run_on'], [{'items_gone_after': 0.0, 'carts_off_after': 4.0}])

    def test_mending_during_the_clear_and_no_next_map(self):
        t = self.trs[2]
        self.assertEqual((t['start'], t['end']), ('2026-02-03T11:10:04', '2026-02-03T11:18:35'))  # FINISH_TILE end
        self.assertEqual(nonzero(t['buckets']), {'COUNT': 3, 'NUKE_BREAK': 353, 'NUKE_EXIT': 30, 'CLEAR': 2,
                                                 'CLEAR_MACHINES': 71, 'CLEAR_RUNON': 30, 'FINISH_TILE': 22})
        self.assertEqual(t['seconds'], 511)
        self.assertEqual(t['mends'], {'in_nuke': {'lines': 0, 'bottles': 0},
                                      'during_clear': {'lines': 3, 'bottles': 40},
                                      'elsewhere': {'lines': 0, 'bottles': 0}, 'waited_for_clear': 1})
        self.assertEqual(t['run_on'], [{'cap_reached': True, 'items_left': 2}])

    def test_summary(self):
        s = tm.summary(self.trs)
        self.assertEqual((s['transitions'], s['left_out']), (2, 1))
        self.assertEqual((s['mean_seconds'], s['mean_external_seconds'], s['mean_seconds_excl_external']),
                         (527.5, 0, 527.5))
        self.assertEqual(s['buckets_mean'], {'COUNT': 3, 'NUKE_BREAK': 298, 'NUKE_MEND': 21, 'NUKE_EXIT': 22.5,
                                             'CLEAR': 1.5, 'CLEAR_MACHINES': 70.5, 'CLEAR_RUNON': 13,
                                             'FINISH_TILE': 22, 'SELECT': 1, 'STOCK_CHECK': 18.5, 'GEAR': 2.5,
                                             'RESTOCK': 54})
        self.assertEqual(s['external_buckets_mean'], {})

    def test_report(self):
        rc, out = run_main([fixture('tail_mixed.log')])
        self.assertEqual(rc, 0)
        self.assertIn('#j0005 demo [0,0] -> #j0005 demo [1,0]\n'
                      '  map done 2026/02/03 10:20:30, first BUILD of the next map 2026/02/03 10:35:40\n'
                      '  tail 0:15:10 (910 s), excl. external waits 0:15:10 (910 s); external 0:00:00\n', out)
        self.assertIn('  nuke: mend stop                0:00:42       42   4.6%\n', out)
        self.assertIn('  nuke: exit walk                0:00:45       45   4.9%\n', out)
        self.assertIn('  clear: machines                0:01:11       71   7.8%\n', out)
        self.assertIn('  NUKE     400 blocks, 3 rounds; 1 mend stop, 2 exit walks\n', out)
        self.assertIn('  mends    in NUKE 2 mends (40 bottles); during the clear 0 mends (0 bottles), 0 waited for the '
                      'clear; elsewhere 0 mends (0 bottles)\n', out)
        self.assertIn('  CLEAR    1 press; clear 71.2 s after the press; farthest sweep chunk 3, simulation '
                      'distance 4\n', out)
        self.assertIn('  STOCK    1 rescan (12 containers); 1 scan (12 containers from 3 stand spots)\n', out)
        self.assertIn('  RESTOCK  1 load, sweep 5 containers from 2 stand spots; 1 failed drop, 1 "could not get '
                      'rid of"\n', out)
        self.assertIn('  NUKE     nothing to break (no "nuking N blocks" line); 0 mend stops, 0 exit walks\n', out)
        self.assertIn('#j0005 demo [2,0] -> (no next map)\n  map done 2026/02/03 11:10:04, FINISH_TILE ended '
                      '2026/02/03 11:18:35', out)
        self.assertIn('during the clear 3 mends (40 bottles), 1 waited for the clear', out)
        self.assertIn('  run-on   cap reached with 2 items left\n', out)
        self.assertIn('Summary: mean over 2 complete tails (1 without a next map left out)\n'
                      '  mean tail 0:08:48 (527.5 s), excl. external waits 0:08:48 (527.5 s); external 0.0 s\n', out)
        self.assertIn('  nuke: breaking                 0:04:58    298.0  56.5%\n', out)


class RestartMaterialsTest(unittest.TestCase):
    """A restart inside WAIT_CLEAR across two log files (the rotated one zipped), then the next map short of materials:
    a materials wait and a re-check before its first BUILD."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        z = os.path.join(cls.tmp, 'proxy-2026-02-04.1.log.zip')
        with zipfile.ZipFile(z, 'w') as zf:
            zf.write(fixture('tail_restart_1.log'), 'proxy-2026-02-04.1.log')
        latest = os.path.join(cls.tmp, 'latest.log')
        shutil.copy(fixture('tail_restart_latest.log'), latest)
        cls.paths = [latest, z]  # out of order: merged by time
        cls.an, cls.files, cls.trs = tm.analyze(cls.paths)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def test_one_tail(self):
        self.assertEqual(len(self.trs), 1)
        t = self.trs[0]
        self.assertEqual((t['from'], t['to'], t['state']), ('#j0006 sample [0,0]', '#j0006 sample [0,1]', 'complete'))
        self.assertEqual((t['start'], t['end']), ('2026-02-04T09:20:02', '2026-02-04T09:40:00'))

    def test_buckets(self):
        t = self.trs[0]
        # WAIT_CLEAR 09:25:31-09:27:32: machines 29 s before the restart + 40 s after it until the clear read,
        # restart 09:26:00-09:26:40, run-on 12 s; materials wait 09:28:40-09:38:40; re-check 09:38:40-09:39:10
        self.assertEqual(nonzero(t['buckets']), {'COUNT': 3, 'NUKE_BREAK': 295, 'NUKE_EXIT': 30, 'CLEAR': 1,
                                                 'CLEAR_MACHINES': 69, 'CLEAR_RUNON': 12, 'FINISH_TILE': 22,
                                                 'SELECT': 1, 'STOCK_CHECK': 45, 'GEAR': 5, 'RESTOCK': 45,
                                                 'RESTART': 40, 'MATERIALS_WAIT': 600, 'MATERIALS_RECHECK': 30})
        self.assertEqual((t['seconds'], t['external_seconds'], t['seconds_excl_external']), (1198, 670, 528))
        self.assertEqual(sum(t['buckets'].values()), t['seconds'])

    def test_details(self):
        t = self.trs[0]
        self.assertEqual(t['clear']['clear_seconds'], 109.0)
        self.assertEqual(t['run_on'], [{'items_gone_after': 9.0, 'carts_off_after': 12.0}])
        self.assertEqual(t['stock_check'], {'rescans': 1, 'rescan_containers': 8, 'scans': 2, 'scanned_containers': 10,
                                            'scan_stand_spots': 3})
        self.assertEqual(t['restock']['loads'], 1)

    def test_directory_and_duplicates(self):
        an, files, trs = tm.analyze([self.tmp] + self.paths)  # the directory and the files again: every line once
        self.assertEqual(len(files), 4)
        self.assertEqual(trs, self.trs)

    def test_report_and_summary(self):
        _, out = run_main(self.paths)
        self.assertIn('  external waits                 0:11:10      670  55.9%\n'
                      '    offline: restart             0:00:40       40   3.3%\n'
                      '    materials wait               0:10:00      600  50.1%\n'
                      '    materials: re-checks         0:00:30       30   2.5%\n', out)
        s = tm.summary(self.trs)
        self.assertEqual((s['transitions'], s['mean_seconds_excl_external']), (1, 528))
        self.assertEqual(s['external_buckets_mean'], {'RESTART': 40, 'MATERIALS_WAIT': 600, 'MATERIALS_RECHECK': 30})


class OutputTest(unittest.TestCase):
    def test_no_log_text_or_coordinates(self):
        triple = re.compile(r'-?\d+\s*,\s*-?\d+\s*,\s*-?\d+')
        for paths in ([fixture('tail_mixed.log')], [fixture('tail_restart_1.log'), fixture('tail_restart_latest.log')]):
            for extra in ([], ['--json']):
                _, out = run_main(extra + paths)
                self.assertIsNone(triple.search(out), '%s %s: %s' % (paths, extra, triple.search(out)))
                for text in ('stored in', 'walking from', 'clicked from', 'moss', 'minecraft:', 'standing at'):
                    self.assertNotIn(text, out, '%s %s' % (paths, extra))

    def test_json(self):
        rc, out = run_main(['--json', fixture('tail_mixed.log')])
        self.assertEqual(rc, 0)
        d = json.loads(out)
        self.assertEqual([t['seconds'] for t in d['transitions']], [910, 145, 511])
        self.assertEqual(d['transitions'][0]['buckets']['NUKE_MEND'], 42)
        self.assertEqual(d['summary']['transitions'], 2)
        self.assertIn('MATERIALS_RECHECK', d['external_buckets'])
        self.assertEqual(d['files'][0]['name'], 'tail_mixed.log')

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
