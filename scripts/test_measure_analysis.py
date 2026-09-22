import queue
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import measure_analysis as measurement


class RecordedEngine:
    timeout = 1

    def __init__(self, lines):
        self.lines = iter(lines)
        self.sent = []

    def send(self, value):
        self.sent.append(value)

    def gtp(self, command):
        self.sent.append(command)

    def json(self, request):
        self.sent.append(request)

    def receive(self, deadline):
        return time.perf_counter(), next(self.lines)


class MeasurementTest(unittest.TestCase):
    def test_alternates_profiles_without_dropping_repeats(self):
        self.assertEqual([(0, 'a'), (0, 'b'), (1, 'b'), (1, 'a'), (2, 'a'), (2, 'b')],
                         measurement.alternating(['a', 'b'], 3))

    def test_realtime_waits_for_matching_stop_ack_not_stream_terminator(self):
        engine = RecordedEngine(['=', '', 'info move D4 visits 10 rootInfo visits 5000',
                                 '', '=99 unrelated', '=999'])
        fixture = dict(boardSize=19, komi=7.5, rules='tromp-taylor', moves=[])
        result = measurement.realtime(engine, fixture, 5000)
        self.assertEqual(5000, result['observedRootVisits'])
        self.assertIn('999 stop', engine.sent)
        self.assertEqual([], list(engine.lines))

    def test_whole_game_rejects_under_budget_completion(self):
        engine = RecordedEngine(['{"id":"game","turnNumber":0,"rootInfo":{"visits":4999},"isDuringSearch":false}'])
        with self.assertRaisesRegex(RuntimeError, 'under-budget'):
            measurement.whole_game(engine, dict(boardSize=19, komi=7.5, rules='tromp-taylor', moves=[]), 5000)

    def test_cancellation_waits_for_all_turns_not_just_ack(self):
        engine = RecordedEngine([
            '{"id":"game","turnNumber":1,"rootInfo":{"visits":5000},"isDuringSearch":false}',
            '{"id":"game","turnNumber":0,"rootInfo":{"visits":5000},"isDuringSearch":false}',
            '{"id":"cancel","turnNumber":0,"isDuringSearch":true}',
            '{"id":"terminate","action":"terminate","terminateId":"cancel"}',
            '{"id":"cancel","turnNumber":0,"isDuringSearch":false}',
            '{"id":"cancel","turnNumber":1,"isDuringSearch":false,"noResults":true}',
        ])
        result = measurement.whole_game(engine, dict(boardSize=19, komi=7.5, rules='tromp-taylor', moves=[['B', 'D4']]), 5000)
        self.assertEqual(2, result['positions'])
        self.assertEqual([], list(engine.lines))

    def test_real_subprocess_early_exit_fails_and_resources_close(self):
        import sys
        with tempfile.TemporaryDirectory() as work:
            engine = measurement.Engine([sys.executable, '-c', 'pass'], Path(work), 1)
            try:
                with self.assertRaisesRegex(RuntimeError, 'exited'):
                    engine.receive(time.perf_counter() + 2)
            finally:
                engine.close()
            self.assertFalse(engine.reader.is_alive())
            self.assertTrue(engine.log.closed)

    def test_no_output_obeys_deadline(self):
        engine = object.__new__(measurement.Engine)
        engine.lines = queue.Queue()
        with self.assertRaises(TimeoutError):
            engine.receive(time.perf_counter())


if __name__ == '__main__':
    unittest.main()
