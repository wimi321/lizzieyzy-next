#!/usr/bin/env python3
"""Read measure_analysis.py evidence without starting engines or changing settings.

Python 3.11+, standard library only. JSON exports contain measurements, not an
instruction or promise to apply them. The application independently assesses
them and matches their fingerprint to the current saved command.
"""
import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import shlex
import statistics
import sys


TUNING_KEYS = frozenset(("numSearchThreads", "numAnalysisThreads",
                         "numSearchThreadsPerAnalysisThread", "nnMaxBatchSize"))
LOCATION_KEYS = frozenset(("homeDataDir", "logDir", "logFile", "logDirDated"))
SCENES = {"realtime": "live", "whole-game": "whole-game"}
PARAMETERS = {"realtime": ("numSearchThreads", "nnMaxBatchSize"),
              "whole-game": ("numAnalysisThreads", "numSearchThreadsPerAnalysisThread",
                             "nnMaxBatchSize")}
HASH = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value, name, minimum=0, positive=False, integer=False):
    require(type(value) in (int, float), name + " must be numeric")
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    require(finite, name + " must be finite numeric")
    require(value > minimum if positive else value >= minimum, name + " is out of range")
    require(not integer or type(value) is int, name + " must be an integer")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def percentile95(samples):
    require(isinstance(samples, list) and samples, "No EDT samples")
    values = sorted(number(value, "EDT sample") for value in samples)
    # Nearest rank, not interpolation; documented so exported values are reproducible.
    return values[math.ceil(len(values) * .95) - 1]


def split_windows_command(command):
    """Inverse of the usual Windows CRT quoting, including spaces/backslashes.

    No shell is invoked. POSIX evidence uses shlex.split instead.
    """
    result, index = [], 0
    while index < len(command):
        while index < len(command) and command[index].isspace():
            index += 1
        if index == len(command):
            break
        token, quoted = [], False
        while index < len(command) and (quoted or not command[index].isspace()):
            slashes = 0
            while index < len(command) and command[index] == "\\":
                slashes += 1
                index += 1
            if index < len(command) and command[index] == '"':
                token.extend("\\" * (slashes // 2))
                if slashes % 2:
                    token.append('"')
                else:
                    quoted = not quoted
                index += 1
            else:
                token.extend("\\" * slashes)
                if index < len(command):
                    if not quoted and command[index].isspace():
                        break
                    token.append(command[index])
                    index += 1
        require(not quoted, "Unclosed quote in effectiveCommand")
        result.append("".join(token))
    return result


def command_details(tokens):
    require(isinstance(tokens, list) and len(tokens) >= 2
            and all(isinstance(item, str) for item in tokens), "Invalid command tokens")
    require(tokens[1] in ("gtp", "analysis"), "Only direct GTP/analysis commands are supported")
    overrides, configs, assets = {}, [], {"engine": tokens[0]}
    index = 2
    while index < len(tokens):
        flag = tokens[index]
        if flag == "-quit-without-waiting":
            index += 1
            continue
        require(flag in ("-model", "--model", "-weights", "--weights", "-config", "--config",
                         "-override-config", "--override-config"), "Unsupported command flag: " + flag)
        require(index + 1 < len(tokens), "Missing command argument")
        value = tokens[index + 1]
        if flag.endswith("override-config"):
            for entry in value.split(","):
                key, _, setting = entry.strip().partition("=")
                if key.strip():
                    overrides[key.strip()] = setting.strip()
        elif flag.endswith("config"):
            configs.append(value)
        else:
            assets["model"] = value
        index += 2
    require("model" in assets and configs, "Missing model/config command argument")
    return {"mode": tokens[1], "assets": assets, "configs": configs, "overrides": overrides,
            "semantics": {key: value for key, value in overrides.items()
                          if key not in TUNING_KEYS | LOCATION_KEYS}}


def effective_tokens(sample, manifest):
    """Prefer the observed process argv; only fall back when OS argv is unavailable."""
    tokens = sample.get("effectiveCommandArgs", [])
    require(isinstance(tokens, list) and all(isinstance(token, str) for token in tokens),
            "Invalid effectiveCommandArgs")
    if tokens:
        return tokens
    command = sample.get("effectiveCommand")
    require(isinstance(command, str) and command, "No final effective command was recorded")
    return (split_windows_command(command) if manifest.get("os", "").startswith("Windows")
            else shlex.split(command))


def telemetry(path):
    """One GPU only: never silently pick a device without a recorded GPU UUID."""
    identity, memories, utilizations, counts, times = None, [], [], [], []
    hidden = 0
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        record = json.loads(line, object_pairs_hook=unique_object)
        require(record.get("gpuExitCode") == 0 and record.get("processesExitCode") == 0,
                f"Incomplete GPU/process telemetry at line {line_number}")
        rows = list(csv.reader(io.StringIO(record.get("gpu", "")), skipinitialspace=True))
        require(len(rows) == 1 and len(rows[0]) == 5, "Single GPU identity is required")
        name, driver, used, total, utilization = [value.strip() for value in rows[0]]
        require(name and driver, "GPU identity is missing")
        current = (name, driver, number(float(total), "GPU memory", positive=True))
        require(identity is None or identity == current, "GPU identity changed within run")
        identity = current
        memories.append(number(float(used), "GPU memory used", positive=True))
        require(memories[-1] <= current[2], "GPU memory exceeds physical total")
        utilizations.append(number(float(utilization), "GPU utilization"))
        require(utilizations[-1] <= 100, "GPU utilization exceeds 100%")
        process_rows = list(csv.reader(io.StringIO(record.get("processes", "")), skipinitialspace=True))
        require(all(len(row) == 3 for row in process_rows), "Malformed process CSV")
        count = sum("katago" in row[1].lower() for row in process_rows)
        require(record.get("visibleKataGoProcessCount") == count, "Visible engine count differs from CSV")
        counts.append(count)
        hidden += sum("[" in row[1] for row in process_rows)
        times.append(number(record.get("monotonicSeconds"), "Telemetry timestamp"))
    require(identity is not None and len(times) >= 2, "Insufficient GPU telemetry samples")
    require(all(left < right for left, right in zip(times, times[1:])), "Unordered telemetry")
    return {"gpuName": identity[0], "driverVersion": identity[1], "memoryMiB": identity[2],
            "maxMemoryMiB": max(memories), "maxEngineProcesses": max(counts),
            "gpuUtilizationMedian": statistics.median(utilizations),
            "gpuUtilizationMax": max(utilizations), "samples": len(times),
            "maxSampleGapSeconds": max(right - left for left, right in zip(times, times[1:])),
            "hiddenProcessObservations": hidden, "start": times[0], "end": times[-1]}


def load_evidence(directory):
    directory = Path(directory).resolve(strict=True)
    manifest = read_json(directory / "manifest.json")
    require(manifest.get("schemaVersion") == 1, "Unsupported measurement manifest")
    require(manifest.get("mode") in ("application", "engine"), "Unknown measurement mode")
    for key in ("engineSha256", "modelSha256", "configSha256", "fixtureSha256"):
        require(isinstance(manifest.get(key), str) and HASH.fullmatch(manifest[key]), "Missing " + key)
    number(manifest.get("budget"), "budget", positive=True, integer=True)
    require(manifest.get("rounds") in (0, 3, 5), "Unsupported round count")
    require(isinstance(manifest.get("fixture", {}).get("moves"), list), "Missing fixture moves")
    samples = read_json(directory / "results.json")
    require(isinstance(samples, list) and samples, "No completed samples")
    seen, loaded = set(), []
    for sample in samples:
        profile, round_number = sample.get("profile"), sample.get("round")
        require(isinstance(profile, str) and re.fullmatch(r"[a-zA-Z0-9_-]+", profile), "Unsafe profile name")
        number(round_number, "round", integer=True)
        require(round_number <= manifest["rounds"], "Run exceeds declared rounds")
        scene = next((key for key in SCENES if sample.get("scene") == (
            "application-" + key if manifest["mode"] == "application" else
            ("realtime-gtp" if key == "realtime" else "whole-game-analysis"))), None)
        require(scene, "Sample scene does not match manifest mode")
        key = (round_number, profile, scene)
        require(key not in seen, "Duplicate profile/round/scene")
        seen.add(key)
        run_dir = directory / f"{round_number:02}-{profile}-{scene}"
        require(read_json(run_dir / "result.json") == sample, "results.json and result.json disagree")
        expected_phase = "warm-process" if round_number else "cold-process-isolated-cache"
        require(sample.get("phase") == expected_phase, "Run phase disagrees with round")
        number(sample.get("seconds"), "seconds", positive=True)
        number(sample.get("startupSeconds"), "startupSeconds")
        errors = []
        gpu = None
        try:
            gpu = telemetry(run_dir / "gpu.jsonl")
        except (ValueError, OSError) as error:
            errors.append(str(error))
        loaded.append({"sample": sample, "scene": scene, "directory": run_dir,
                       "gpu": gpu, "errors": errors})
    return {"directory": directory, "manifest": manifest, "loaded": loaded}


def paired_runs(evidence, scene, baseline, candidate):
    manifest = evidence["manifest"]
    require(manifest["rounds"] in (3, 5), "Exactly 3 or 5 warm rounds required")
    rows = [row for row in evidence["loaded"] if row["scene"] == scene
            and row["sample"]["round"] > 0
            and row["sample"]["profile"] in (baseline, candidate)]
    require(len(rows) == manifest["rounds"] * 2, "Incomplete warm pairs")
    first = rows[0]["sample"]["profile"]
    second = candidate if first == baseline else baseline
    for index, row in enumerate(rows):
        round_number = index // 2 + 1
        order = (first, second) if round_number % 2 else (second, first)
        require((row["sample"]["round"], row["sample"]["profile"]) ==
                (round_number, order[index % 2]), "Nonconsecutive or nonalternating warm order")
    valid_gpu = [row["gpu"] for row in rows if row["gpu"]]
    require(all(left["end"] < right["start"] for left, right in zip(valid_gpu, valid_gpu[1:])),
            "Warm process telemetry windows overlap or contradict execution order")
    return rows


def export_report(evidence, scene, baseline="baseline", candidate="candidate"):
    manifest = evidence["manifest"]
    require(manifest["mode"] == "application", "Engine-only evidence cannot become an application report")
    rows = paired_runs(evidence, scene, baseline, candidate)
    # input.cfg is the historical primary config copy, not today's possibly changed config.
    config = (evidence["directory"] / "input.cfg").read_bytes()
    require(hashlib.sha256(config).hexdigest() == manifest["configSha256"], "Archived config hash mismatch")
    require(not any(line.split("#", 1)[0].strip().startswith("@")
                    for line in config.decode("utf-8-sig").splitlines()),
            "Historical include contents were not archived; cannot construct configIncludes")
    fingerprint, parameters, result_rows, asset_identity = None, {}, [], None
    for row in rows:
        sample, gpu = row["sample"], row["gpu"]
        require(not row["errors"], "; ".join(row["errors"]))
        require(sample.get("status") == "PASS", "Application probe did not pass")
        raw = read_json(row["directory"] / "app-result.json")
        require(all(sample.get(key) == value for key, value in raw.items()), "App/result evidence mismatch")
        require(raw.get("status") == "PASS", "Raw application probe did not pass")
        require(raw.get("measurementContractVersion") == 2,
                "Probe measurement contract version 2 is required; legacy restore timing is not certified")
        tokens = effective_tokens(sample, manifest)
        actual = command_details(tokens)
        original = command_details(read_json(row["directory"] / "command.json"))
        require(actual["mode"] == ("gtp" if scene == "realtime" else "analysis"), "Wrong launch mode")
        require(actual["assets"] == original["assets"] and actual["configs"] == original["configs"],
                "Application changed measured assets")
        identity = (actual["assets"], actual["configs"])
        require(asset_identity is None or asset_identity == identity, "Assets differ between runs")
        asset_identity = identity
        require(len(actual["configs"]) == 1, "Extra command configs were not archived")
        current = {key: manifest[key] for key in ("engineSha256", "modelSha256", "configSha256")}
        current.update({key: gpu[key] for key in ("gpuName", "driverVersion", "memoryMiB")})
        current.update(configIncludes=[], commandSemantics=actual["semantics"])
        require(fingerprint is None or fingerprint == current, "Fingerprint/search semantics differ between runs")
        fingerprint = current
        profile = "baseline" if sample["profile"] == baseline else "candidate"
        params = {}
        for key in PARAMETERS[scene]:
            value = actual["overrides"].get(key, "")
            require(re.fullmatch(r"[1-9][0-9]*", value), "Missing explicit numeric effective " + key)
            params[key] = int(value)
            require(params[key] <= (65536 if key == "nnMaxBatchSize" else 4096), "Tuning parameter too large")
        if scene == "whole-game":
            require(actual["overrides"].get("numSearchThreads", "") == "", "Whole-game GTP thread alias not cleared")
        require(profile not in parameters or parameters[profile] == params, "Parameters changed within profile")
        parameters[profile] = params
        delays = raw.get("edtLatencySeconds")
        edt_p95 = percentile95(delays)
        require(raw.get("edtSamples") == len(delays), "EDT sample count mismatch")
        response_key = "pauseAckSeconds" if scene == "realtime" else "cancelCompleteSeconds"
        result = {"profile": profile, "round": sample["round"], "phase": "warm",
                  "seconds": sample["seconds"], "responseSeconds": number(raw.get(response_key), response_key),
                  "edtP95Seconds": edt_p95, "maxMemoryMiB": gpu["maxMemoryMiB"],
                  "maxEngineProcesses": gpu["maxEngineProcesses"]}
        if scene == "realtime":
            first = number(raw.get("firstResultSeconds"), "first result", positive=True)
            require(first <= result["seconds"], "First result after completion")
            result.update(firstResultSeconds=first,
                          observedRootVisits=number(raw.get("observedRootVisits"), "root visits", integer=True))
        else:
            require(raw.get("cancellationMethod") == "production-worker-process-exit", "Unknown cancellation metric")
            visits = raw.get("observedVisits")
            require(isinstance(visits, list) and len(visits) == len(manifest["fixture"]["moves"]) + 1
                    and raw.get("positions") == len(visits), "Incomplete per-position visit evidence")
            result["rootVisitsByTurn"] = [number(value, "root visits", integer=True) for value in visits]
        result_rows.append(result)
    return {"schemaVersion": 1, "scene": SCENES[scene], "measurementMode": "application",
            "fingerprint": fingerprint, "fixtureSha256": manifest["fixtureSha256"],
            "budget": manifest["budget"], "positions": 1 if scene == "realtime" else
            len(manifest["fixture"]["moves"]) + 1, "metricScope": "totalGpu",
            "baselineParameters": parameters["baseline"], "candidateParameters": parameters["candidate"],
            "runs": result_rows}


def coefficient_of_variation(values):
    scale = max(values)
    scaled = [value / scale for value in values]
    return statistics.stdev(scaled) / statistics.mean(scaled)


def assess(report):
    """Same documented numerical gates; the application remains authoritative."""
    baseline = [row for row in report["runs"] if row["profile"] == "baseline"]
    candidate = [row for row in report["runs"] if row["profile"] == "candidate"]
    ratios = [left["seconds"] / right["seconds"] for left, right in zip(baseline, candidate)]
    reasons = []
    if not all(math.isfinite(value) and value > 1 for value in ratios):
        reasons.append("Not every warm pair improved")
    speedup = statistics.median(ratios)
    if not math.isfinite(speedup) or speedup < 1.03:
        reasons.append("Median paired speedup is below 1.03x")
    cvs = [coefficient_of_variation([row["seconds"] for row in group]) for group in (baseline, candidate)]
    if max(cvs) > (.10 if len(baseline) == 3 else .15):
        reasons.append("Timing variation requires five rounds" if len(baseline) == 3 else "Five-round timing variation exceeds 15%")
    if report["baselineParameters"] == report["candidateParameters"]:
        reasons.append("Candidate parameters are unchanged")
    for row in report["runs"]:
        visits = row.get("rootVisitsByTurn", [row.get("observedRootVisits")])
        if any(value < report["budget"] for value in visits):
            reasons.append("Incomplete analysis budget")
        if row["maxEngineProcesses"] != 1:
            reasons.append("Visible engine process maximum is not one")
    metrics = ["responseSeconds", "edtP95Seconds"]
    if report["scene"] == "live":
        metrics.append("firstResultSeconds")
    for metric in metrics:
        before, after = ([row[metric] for row in group] for group in (baseline, candidate))
        if statistics.median(after) > statistics.median(before) * 1.10 + .005:
            reasons.append(metric + " median regressed")
        if max(after) > max(before) * 1.25 + .020:
            reasons.append(metric + " tail regressed")
    before_memory = max(row["maxMemoryMiB"] for row in baseline)
    after_memory = max(row["maxMemoryMiB"] for row in candidate)
    if after_memory > before_memory * 1.15 + 256:
        reasons.append("Peak total GPU memory regressed")
    if after_memory > report["fingerprint"]["memoryMiB"] * .90:
        reasons.append("Less than 10% physical GPU memory remains")
    return {"eligible": not reasons, "reasons": list(dict.fromkeys(reasons)), "speedup": speedup,
            "pairedRatios": ratios, "baselineCV": cvs[0], "candidateCV": cvs[1]}


def markdown(evidence, reports, errors, note):
    manifest = evidence["manifest"]
    lines = ["# Fixed-budget measurement summary", "", note, "",
             "This report concerns only the measured commands, not the current daily configuration. "
             "No settings were changed. A numeric gate pass is not a configuration match or an approval to apply.", "",
             f"Mode: `{manifest['mode']}`. Budget: {manifest['budget']} visits per position. "
             f"Declared warm rounds: {manifest['rounds']}. Source: `{manifest.get('sourceCommit', 'not recorded')}`.", "",
             f"Fixture: board {manifest['fixture'].get('boardSize', 'not recorded')}, "
             f"rules `{manifest['fixture'].get('rules', 'not recorded')}`, "
             f"komi {manifest['fixture'].get('komi', 'not recorded')}, "
             f"{len(manifest['fixture']['moves'])} moves. Recorded analysis options: "
             f"`{json.dumps(manifest.get('analysisOptions', {}), sort_keys=True)}`.", "",
             "| Identity | SHA-256 |", "| --- | --- |"]
    lines += [f"| {key} | `{manifest[key]}` |" for key in
              ("engineSha256", "modelSha256", "configSha256", "fixtureSha256")]
    lines += ["", "## Sampling limits", "",
              "GPU memory/utilization are total-device samples across the entire process lifetime, "
              "including startup, warm-up and cancellation; they are not isolated search-window or per-process peaks. "
              "Sampling can miss short peaks. Engine counts are the maximum *visible* KataGo names in NVIDIA telemetry; "
              "hidden/permission-denied processes are unknown, never counted as proven KataGo. "
              "Zero visible engines during startup/shutdown is normal. Background workload is not subtracted.", "",
              "EDT p95 uses nearest rank within each recorded measured search. Whole-game cancellation is "
              "production worker process exit, not bare JSON terminate acknowledgment. Cold startup timing has "
              "scene-specific scope: for whole-game, application startup ends before its analysis engine loads.", ""]
    for phase, title in ((0, "Cold process / isolated cache (excluded from tuning)"),
                         (1, "Warm process (execution order retained)")):
        lines += ["## " + title, "", "| Scene | Round | Profile | Search s | Startup s | First s | Pause/cancel s | EDT p95 s | Total GPU MiB | Visible engines | Root visits |",
                  "| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
        for row in evidence["loaded"]:
            sample, gpu = row["sample"], row["gpu"]
            if bool(sample["round"]) != bool(phase):
                continue
            edt = percentile95(sample["edtLatencySeconds"]) if sample.get("edtLatencySeconds") else None
            values = (sample["seconds"], sample["startupSeconds"], sample.get("firstResultSeconds"),
                      sample.get("pauseAckSeconds", sample.get("cancelCompleteSeconds")), edt,
                      gpu["maxMemoryMiB"] if gpu else None, gpu["maxEngineProcesses"] if gpu else None)
            cells = ["n/a" if value is None else f"{value:.6g}" for value in values]
            visits = sample.get("observedVisits", sample.get("rootVisitsByTurn", sample.get("observedRootVisits")))
            if isinstance(visits, dict):
                visits = [visits[key] for key in sorted(visits, key=int)]
            cells.append(json.dumps(visits) if visits is not None else "not recorded")
            lines.append(f"| {row['scene']} | {sample['round']} | {sample['profile']} | " + " | ".join(cells) + " |")
        lines.append("")
    for scene, report in reports.items():
        assessment = assess(report)
        lines += ["## " + SCENES[scene] + " warm comparison", "",
                  f"Baseline parameters: `{json.dumps(report['baselineParameters'], sort_keys=True)}`. "
                  f"Candidate: `{json.dumps(report['candidateParameters'], sort_keys=True)}`.", "",
                  "Paired baseline/candidate time ratios: " + ", ".join(f"{ratio:.4f}x" for ratio in assessment["pairedRatios"]) + ". "
                  f"Median: {assessment['speedup']:.4f}x. Sample timing CV: baseline {assessment['baselineCV']:.2%}, "
                  f"candidate {assessment['candidateCV']:.2%}.", "",
                  "Numerical gates: " + ("PASS (application fingerprint review still required)." if assessment["eligible"] else
                                          "REJECT — " + "; ".join(assessment["reasons"]) + "."), "",
                  "Bound non-concurrency overrides (not applied by this tool):", "", "```json",
                  json.dumps(report["fingerprint"]["commandSemantics"], indent=2, sort_keys=True), "```", ""]
        lines += ["| Profile | Median search s | Median throughput | First median/max s | Response median/max s | EDT p95 median/max s | Peak total GPU MiB |",
                  "| --- | ---: | --- | --- | --- | --- | ---: |"]
        for profile in ("baseline", "candidate"):
            samples = [row for row in report["runs"] if row["profile"] == profile]
            cells = []
            for key in ("firstResultSeconds", "responseSeconds", "edtP95Seconds"):
                values = [row[key] for row in samples if key in row]
                cells.append(f"{statistics.median(values):.6g}/{max(values):.6g}" if values else "n/a")
            throughput = statistics.median([
                (row["observedRootVisits"] if scene == "realtime" else report["positions"]) / row["seconds"]
                for row in samples])
            unit = "observed root visits/s" if scene == "realtime" else "positions/s"
            lines.append(f"| {profile} | {statistics.median(row['seconds'] for row in samples):.6g} | "
                         f"{throughput:.6g} {unit} | " + " | ".join(cells) +
                         f" | {max(row['maxMemoryMiB'] for row in samples):.6g} |")
        lines.append("")
    lines += ["## Evidence completeness", ""]
    lines += [f"- {scene}: {error}" for scene, error in errors.items()]
    for row in evidence["loaded"]:
        if row["gpu"]:
            gpu = row["gpu"]
            lines.append(f"- {row['directory'].name}: {gpu['samples']} GPU samples; max gap "
                         f"{gpu['maxSampleGapSeconds']:.3f} s; utilization median/max "
                         f"{gpu['gpuUtilizationMedian']:g}/{gpu['gpuUtilizationMax']:g}%; "
                         f"{gpu['hiddenProcessObservations']} hidden-process observations.")
        else:
            lines.append(f"- {row['directory'].name}: " + "; ".join(row["errors"]))
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--baseline", default="baseline")
    parser.add_argument("--candidate", default="candidate")
    parser.add_argument("--markdown", type=Path, help="New output file; otherwise stdout")
    parser.add_argument("--export-dir", type=Path, help="New directory for complete application measurement JSON")
    parser.add_argument("--note", default="Evidence has not been independently certified by this summary tool.")
    parser.add_argument("--invalid-reason", help="Known measurement defect: suppress all import JSON exports")
    args = parser.parse_args(argv)
    try:
        require(args.baseline != args.candidate, "Baseline and candidate must differ")
        evidence = load_evidence(args.evidence)
        reports, errors = {}, {}
        for scene in SCENES:
            if not any(row["scene"] == scene for row in evidence["loaded"]):
                continue
            try:
                require(not args.invalid_reason, args.invalid_reason or "")
                reports[scene] = export_report(evidence, scene, args.baseline, args.candidate)
            except (ValueError, OSError) as error:
                errors[scene] = str(error)
        for target in (args.markdown, args.export_dir):
            if target:
                require(not target.resolve().is_relative_to(evidence["directory"]),
                        "Outputs must be outside read-only measurement evidence")
                require(not target.exists(), "Refusing to overwrite: " + str(target))
        output = markdown(evidence, reports, errors, args.note)
        if args.markdown:
            with args.markdown.open("x", encoding="utf-8", newline="\n") as destination:
                destination.write(output)
        else:
            print(output, end="")
        if args.export_dir:
            require(reports and not errors, "No complete trustworthy export set: " + json.dumps(errors))
            args.export_dir.mkdir(parents=True, exist_ok=False)
            with (args.export_dir / "README.md").open("x", encoding="utf-8", newline="\n") as destination:
                destination.write(output)
            for scene, report in reports.items():
                with (args.export_dir / (SCENES[scene] + ".json")).open("x", encoding="utf-8", newline="\n") as destination:
                    json.dump(report, destination, indent=2, ensure_ascii=False, allow_nan=False)
                    destination.write("\n")
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print("Measurement summary error: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
