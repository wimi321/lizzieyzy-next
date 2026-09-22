#!/usr/bin/env python3
"""Opt-in, fixed-budget engine baseline. Never writes application settings.

Python 3.11+, no third-party packages. Raw results are retained; cold and hot
samples must not be pooled. This measures engine/protocol time, not GUI latency.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import re
import shlex
import signal
import subprocess
import threading
import time


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def alternating(profiles, rounds):
    return [(run, profile) for run in range(rounds)
            for profile in (profiles if run % 2 == 0 else profiles[::-1])]


def validate_fixture(fixture):
    # The production-window entry is a 19x19, alternating-move benchmark, not an SGF importer.
    if fixture.get('boardSize') != 19 or fixture.get('rules') != 'tromp-taylor' or fixture.get('komi') != 7.5:
        raise ValueError('Measurement fixture requires 19x19, tromp-taylor, komi 7.5')
    if not isinstance(fixture.get('moves'), list):
        raise ValueError('Fixture moves must be a list')
    for index, move in enumerate(fixture['moves']):
        if not isinstance(move, list) or len(move) != 2 or move[0] != ('B' if index % 2 == 0 else 'W'):
            raise ValueError('Fixture must alternate B/W from Black')
        if not isinstance(move[1], str) or not re.fullmatch(r'(?:[A-HJ-T](?:[1-9]|1[0-9])|pass)', move[1]):
            raise ValueError('Fixture has an invalid vertex')


class Engine:
    def __init__(self, command, directory, timeout):
        self.timeout = timeout
        self.lines = queue.Queue()
        self.log = (directory / 'stdout.log').open('w', encoding='utf-8')
        self.err = (directory / 'stderr.log').open('w', encoding='utf-8')
        self.process = None
        try:
            self.process = subprocess.Popen(
                command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.err,
                text=True, encoding='utf-8', errors='replace', bufsize=1,
                cwd=str(directory),
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        except BaseException:
            self.log.close()
            self.err.close()
            raise
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.log.write(line)
                self.lines.put((time.perf_counter(), line.strip()))
        finally:
            self.lines.put((time.perf_counter(), None))

    def send(self, value):
        self.process.stdin.write(value + '\n')
        self.process.stdin.flush()

    def receive(self, deadline):
        try:
            timestamp, line = self.lines.get(timeout=max(0, deadline - time.perf_counter()))
        except queue.Empty:
            raise TimeoutError('Engine response deadline exceeded') from None
        if line is None:
            raise RuntimeError('Engine exited before completing the request; inspect stderr.log')
        if line.startswith('?'):
            raise RuntimeError(line)
        return timestamp, line

    def gtp(self, command):
        self.send(command)
        deadline = time.perf_counter() + self.timeout
        response = []
        while True:
            _, line = self.receive(deadline)
            if not line and response:
                return response
            if line:
                response.append(line)

    def json(self, request):
        self.send(json.dumps(request))
        deadline = time.perf_counter() + self.timeout
        while True:
            _, line = self.receive(deadline)
            if not line:
                continue
            value = json.loads(line)
            if 'error' in value:
                raise RuntimeError(value)
            if value.get('id') == request['id']:
                return value

    def close(self):
        try:
            self.process.stdin.close()
            self.process.wait(timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait(timeout=10)
        finally:
            self.reader.join(timeout=10)
            self.process.stdout.close()
            self.log.close()
            self.err.close()


def telemetry(stop, destination):
    with destination.open('w', encoding='utf-8') as output:
        while not stop.is_set():
            record = {'monotonicSeconds': time.perf_counter()}
            for key, query in [('gpu', '--query-gpu=name,driver_version,memory.used,memory.total,utilization.gpu'),
                               ('processes', '--query-compute-apps=pid,process_name,used_memory')]:
                try:
                    result = subprocess.run(['nvidia-smi', query, '--format=csv,noheader,nounits'],
                                            capture_output=True, text=True, timeout=5,
                                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                    record[key] = result.stdout.strip()
                    record[key + 'ExitCode'] = result.returncode
                except (OSError, subprocess.TimeoutExpired) as error:
                    record[key + 'Error'] = str(error)
            if record.get('processesExitCode') == 0:
                record['visibleKataGoProcessCount'] = sum(
                    'katago' in row.lower() for row in record.get('processes', '').splitlines())
            output.write(json.dumps(record) + '\n')
            output.flush()
            stop.wait(1)


def realtime(engine, fixture, budget):
    engine.gtp('clear_board')
    engine.gtp('boardsize ' + str(fixture['boardSize']))
    engine.gtp('komi ' + str(fixture['komi']))
    engine.gtp('kata-set-rules ' + fixture['rules'])
    for color, vertex in fixture['moves'][:-1]:
        engine.gtp('play ' + color + ' ' + vertex)
    engine.gtp('clear_cache')
    start = time.perf_counter()
    if fixture['moves']:
        color, vertex = fixture['moves'][-1]
        engine.gtp('play ' + color + ' ' + vertex)
    engine.send('kata-analyze interval 10 rootInfo true ownership true movesOwnership true pvVisits true')
    first = None
    visits = 0
    deadline = start + engine.timeout
    while True:
        timestamp, line = engine.receive(deadline)
        if 'info move ' in line:
            # rootInfo gives total visits, not the sum of a truncated move list.
            match = re.search(r'rootInfo\s+visits\s+(\d+)', line)
            if match:
                visits = int(match.group(1))
                if first is None and visits > 0:
                    first = timestamp - start
        if visits >= budget:
            break
    elapsed = timestamp - start
    if first is None:
        raise RuntimeError('No valid analysis received')
    stop_start = time.perf_counter()
    engine.send('999 stop')
    # The empty line ending streaming analysis is not the stop acknowledgement.
    deadline = stop_start + engine.timeout
    while True:
        stop_timestamp, line = engine.receive(deadline)
        if line.startswith('=999'):
            break
    engine.gtp('clear_cache')
    return {'scene': 'realtime-gtp', 'seconds': elapsed, 'firstResultSeconds': first,
            'observedRootVisits': visits, 'budget': budget, 'visitsPerSecond': visits / elapsed,
            'pauseAckSeconds': stop_timestamp - stop_start}


def whole_game(engine, fixture, budget):
    engine.json({'id': 'clear', 'action': 'clear_cache'})
    turns = list(range(len(fixture['moves']) + 1))
    request = dict(id='game', boardXSize=fixture['boardSize'], boardYSize=fixture['boardSize'],
                   moves=fixture['moves'], rules=fixture['rules'], komi=fixture['komi'],
                   maxVisits=budget, analyzeTurns=turns, includeOwnership=True,
                   includeMovesOwnership=True, includePVVisits=True,
                   overrideSettings={'reportAnalysisWinratesAs': 'SIDETOMOVE'})
    start = time.perf_counter()
    engine.send(json.dumps(request))
    deadline = start + engine.timeout
    complete = {}
    first = None
    while len(complete) < len(turns):
        timestamp, line = engine.receive(deadline)
        if not line:
            continue
        value = json.loads(line)
        if 'error' in value:
            raise RuntimeError(value)
        if value.get('id') != 'game':
            continue
        if first is None and value.get('rootInfo', {}).get('visits', 0) > 0:
            first = timestamp - start
        if not value.get('isDuringSearch', False):
            turn = value['turnNumber']
            visits = value.get('rootInfo', {}).get('visits', 0)
            if turn not in turns or turn in complete or visits < budget:
                raise RuntimeError('Unexpected, duplicate, or under-budget final response')
            complete[turn] = visits
    elapsed = timestamp - start
    # Test cancellation separately; it does not replace any fixed-budget sample.
    request.update(id='cancel', maxVisits=1000000000, analyzeTurns=turns, reportDuringSearchEvery=0.1)
    engine.send(json.dumps(request))
    deadline = time.perf_counter() + engine.timeout
    while True:
        _, line = engine.receive(deadline)
        if line and json.loads(line).get('id') == 'cancel':
            break
    cancel_start = time.perf_counter()
    engine.send(json.dumps(dict(id='terminate', action='terminate', terminateId='cancel')))
    cancelled = set()
    deadline = cancel_start + engine.timeout
    while len(cancelled) < len(turns):
        timestamp, line = engine.receive(deadline)
        if not line:
            continue
        value = json.loads(line)
        if value.get('id') == 'cancel' and not value.get('isDuringSearch', False):
            cancelled.add(value['turnNumber'])
    return {'scene': 'whole-game-analysis', 'seconds': elapsed, 'firstResultSeconds': first,
            'positions': len(turns), 'positionsPerSecond': len(turns) / elapsed,
            'rootVisitsByTurn': complete, 'cancelCompleteSeconds': timestamp - cancel_start,
            'cancellationMethod': 'json-terminate-all-final-responses'}


def application(args, directory, scene, command, fixture, warm):
    """Uses the real production UI and engine adapters via an opt-in test entry point."""
    request = directory / 'app-request.json'
    write_json(request, dict(scene=scene, command=subprocess.list2cmdline(command) if os.name == 'nt'
                             else shlex.join(command), fixture=fixture, visits=args.visits, warm=warm))
    invocation = [args.java, '-Djava.awt.headless=false', '-cp', args.app_classpath,
                  'featurecat.lizzie.gui.PerformanceMeasurementProbe', str(request)]
    with (directory / 'app-stdout.log').open('w', encoding='utf-8') as stdout, \
            (directory / 'app-stderr.log').open('w', encoding='utf-8') as stderr:
        process = subprocess.Popen(invocation, cwd=directory, stdout=stdout, stderr=stderr,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
                                   start_new_session=os.name != 'nt')
        try:
            code = process.wait(timeout=args.timeout * 4)
        except BaseException:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
            raise
    result = json.loads((directory / 'app-result.json').read_text(encoding='utf-8'))
    if code or result.get('status') != 'PASS':
        raise RuntimeError(f'Application measurement failed: {result}')
    result['scene'] = 'application-' + scene
    return result


def run(args):
    engine_path, model, config = (Path(p).resolve(strict=True) for p in (args.engine, args.model, args.config))
    fixture = json.loads(Path(args.fixture).read_text(encoding='utf-8'))
    validate_fixture(fixture)
    profiles = json.loads(Path(args.profiles).read_text(encoding='utf-8'))
    if not profiles or any(not re.fullmatch(r'[a-zA-Z0-9_-]+', p['name']) for p in profiles):
        raise ValueError('Profiles require safe, non-empty names')
    if len({p['name'] for p in profiles}) != len(profiles):
        raise ValueError('Profile names must be unique')
    allowed = {'numSearchThreads', 'numAnalysisThreads', 'numSearchThreadsPerAnalysisThread', 'nnMaxBatchSize'}
    for profile in profiles:
        if set(profile['parameters']) - allowed or any(type(v) is not int or v < 1 for v in profile['parameters'].values()):
            raise ValueError('Only positive integer concurrency/batch parameters may vary')
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / 'manifest.json', {
        'schemaVersion': 1, 'os': platform.platform(), 'python': platform.python_version(),
        'engineSha256': digest(engine_path), 'modelSha256': digest(model), 'configSha256': digest(config),
        'fixtureSha256': digest(args.fixture), 'profilesSha256': digest(args.profiles), 'fixture': fixture, 'profiles': profiles,
        'budget': args.visits, 'rounds': args.rounds, 'sourceCommit': args.source_commit,
        'analysisOptions': dict(ownership=True, movesOwnership=True, pvVisits=True, analysisPVLen=100,
                                gtpIntervalCentisec=10, jsonDuringSearch=False),
        'mode': 'application' if args.app_classpath else 'engine',
        'limits': 'Compare only matching manifest inputs and scenes; cold samples are not tuning evidence.'})
    (output / 'input.cfg').write_bytes(config.read_bytes())
    results = []
    for round_number, profile in alternating(profiles, args.rounds + 1):
        for scene in (('realtime', 'whole-game') if args.scene == 'both' else (args.scene,)):
            directory = output / f"{round_number:02}-{profile['name']}-{scene}"
            directory.mkdir()
            engine_home = output / (profile['name'] + '-' + scene + '-cache')
            engine_home.mkdir(exist_ok=True)
            parameters = dict(profile['parameters'])
            if scene == 'realtime':
                parameters.pop('numAnalysisThreads', None)
                parameters.pop('numSearchThreadsPerAnalysisThread', None)
                parameters.update(maxVisits=args.visits, maxPlayouts=1000000000, maxTime=1000000000,
                                  maxVisitsPondering=args.visits, maxPlayoutsPondering=1000000000,
                                  maxTimePondering=1000000000, ponderingEnabled='false')
            else:
                parameters['numSearchThreads'] = ''
            parameters['homeDataDir'] = engine_home.as_posix()
            parameters['logToStderr'] = 'true'
            parameters['analysisPVLen'] = 100
            parameters['logDir'] = (directory / 'engine-logs').as_posix()
            command = [str(engine_path), 'gtp' if scene == 'realtime' else 'analysis',
                       '-model', str(model), '-config', str(config), '-override-config',
                       ','.join(f'{k}={v}' for k, v in parameters.items())]
            write_json(directory / 'command.json', command)
            stop = threading.Event()
            monitor = threading.Thread(target=telemetry, args=(stop, directory / 'gpu.jsonl'), daemon=True)
            monitor.start()
            engine = None
            try:
                if args.app_classpath:
                    sample = application(args, directory, scene, command, fixture, round_number > 0)
                else:
                    start = time.perf_counter()
                    engine = Engine(command, directory, args.timeout)
                    version = engine.gtp('version') if scene == 'realtime' else engine.json({'id': 'version', 'action': 'query_version'})
                    startup = time.perf_counter() - start
                    measurement = realtime if scene == 'realtime' else whole_game
                    if round_number > 0:
                        # Warm the loaded process too; a disk-cache hit alone is not a hot search.
                        write_json(directory / 'warmup.json', measurement(engine, fixture, args.visits))
                    sample = measurement(engine, fixture, args.visits)
                    sample.update(startupSeconds=startup, version=version, pid=engine.process.pid)
                sample.update(round=round_number, profile=profile['name'],
                              phase='cold-process-isolated-cache' if round_number == 0 else 'warm-process',
                              parameters=parameters)
                results.append(sample)
                write_json(directory / 'result.json', sample)
                write_json(output / 'results.json', results)
                print(f"{directory.name}: {sample['seconds']:.3f}s", flush=True)
            finally:
                if engine:
                    engine.close()
                stop.set()
                monitor.join(timeout=12)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('engine', 'model', 'config', 'fixture', 'profiles', 'output', 'source-commit'):
        parser.add_argument('--' + key, required=True)
    parser.add_argument('--visits', type=int, default=5000)
    parser.add_argument('--rounds', type=int, choices=(0, 3, 5), default=3,
                        help='Warm alternating rounds; 0 is a cold-only smoke check, never tuning evidence')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--scene', choices=('both', 'realtime', 'whole-game'), default='both')
    parser.add_argument('--app-classpath', help='Absolute test/runtime classpath from Maven; opts into real GUI measurement')
    parser.add_argument('--java', default='java', help='Java executable for the optional production-window probe')
    arguments = parser.parse_args()
    if arguments.visits <= 0 or arguments.timeout <= 0:
        parser.error('Visits and timeout must be positive')
    run(arguments)
