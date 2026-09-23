#!/usr/bin/env python3
"""Synthetic tests only: no engines, GPU queries, Java or settings access."""
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import summarize_measured_analysis as summary


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.config = b"# no include\nnumSearchThreads = 4\n"
        (self.evidence / "input.cfg").write_bytes(self.config)
        self.manifest = {
            "schemaVersion": 1, "mode": "application", "os": "Windows-11", "budget": 100,
            "rounds": 3, "fixture": {"moves": [["B", "Q16"]]},
            "engineSha256": "a" * 64, "modelSha256": "b" * 64,
            "configSha256": hashlib.sha256(self.config).hexdigest(),
            "fixtureSha256": "c" * 64, "sourceCommit": "synthetic-only",
        }
        self.samples = []
        timestamp = 1
        for round_number in range(4):
            profiles = ("baseline", "candidate") if round_number % 2 == 0 else ("candidate", "baseline")
            for profile in profiles:
                for scene in ("realtime", "whole-game"):
                    run_dir = self.evidence / f"{round_number:02}-{profile}-{scene}"
                    run_dir.mkdir()
                    multiplier = 1 if profile == "baseline" else 2
                    params = {"numSearchThreads": 8 * multiplier, "nnMaxBatchSize": 16 * multiplier}
                    if scene == "whole-game":
                        params.update(numSearchThreads="", numAnalysisThreads=4 * multiplier,
                                      numSearchThreadsPerAnalysisThread=2)
                    else:
                        params.update(maxVisits=100, maxVisitsPondering=100,
                                      maxTime=1000000000, maxPlayouts=1000000000)
                    params.update(analysisPVLen=100, logToStderr="true", logSearchInfo="false",
                                  homeDataDir=str(run_dir / "cache"), logDir=str(run_dir / "logs"))
                    command = [r"C:\Path with spaces\katago.exe", "gtp" if scene == "realtime" else "analysis",
                               "-model", r"C:\weights\中文.bin.gz", "-config", r"C:\cfg\gtp.cfg",
                               "-override-config", ",".join(f"{key}={value}" for key, value in params.items())]
                    raw = {"status": "PASS", "measurementContractVersion": 2,
                           "seconds": 10.0 if profile == "baseline" else 8.0,
                           "startupSeconds": 2.0, "effectiveCommand": subprocess.list2cmdline(command),
                           "edtSamples": 20, "edtLatencySeconds": [.001] * 20}
                    if scene == "realtime":
                        raw.update(firstResultSeconds=.1, pauseAckSeconds=.02, observedRootVisits=105)
                    else:
                        raw.update(positions=2, observedVisits=[101, 102], cancelCompleteSeconds=.01,
                                   cancellationMethod="production-worker-process-exit")
                    sample = dict(raw, round=round_number, profile=profile, scene="application-" + scene,
                                  phase="warm-process" if round_number else "cold-process-isolated-cache",
                                  parameters=params)
                    self.write(run_dir / "app-result.json", raw)
                    self.write(run_dir / "command.json", command)
                    self.samples.append(sample)
                    records = [{"monotonicSeconds": timestamp + index,
                                "gpu": "NVIDIA RTX Test, 591.86, 4096, 32000, 70", "gpuExitCode": 0,
                                "processes": "12, [Insufficient Permissions], [N/A]" +
                                ("\n55, C:\\engines\\katago.exe, [N/A]" if index else ""),
                                "processesExitCode": 0, "visibleKataGoProcessCount": int(index > 0)}
                               for index in range(3)]
                    (run_dir / "gpu.jsonl").write_text("\n".join(json.dumps(row) for row in records), encoding="utf-8")
                    timestamp += 20
        self.flush()

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def flush(self):
        self.write(self.evidence / "manifest.json", self.manifest)
        self.write(self.evidence / "results.json", self.samples)
        for sample in self.samples:
            path = self.evidence / f"{sample['round']:02}-{sample['profile']}-{sample['scene'].removeprefix('application-')}"
            self.write(path / "result.json", sample)

    def load(self):
        return summary.load_evidence(self.evidence)

    def report(self, scene="realtime"):
        return summary.export_report(self.load(), scene)

    def mutate_raw(self, scene, key, value):
        sample = next(row for row in self.samples if row["round"] == 1
                      and row["profile"] == "candidate" and row["scene"] == "application-" + scene)
        sample[key] = value
        path = self.evidence / f"01-candidate-{scene}" / "app-result.json"
        raw = summary.read_json(path)
        raw[key] = value
        self.write(path, raw)
        self.flush()

    def test_live_export_excludes_cold_and_keeps_true_order(self):
        report = self.report()
        self.assertEqual("live", report["scene"])
        self.assertEqual("application", report["measurementMode"])
        self.assertEqual([1, 1, 2, 2, 3, 3], [row["round"] for row in report["runs"]])
        self.assertEqual(["candidate", "baseline", "baseline", "candidate", "candidate", "baseline"],
                         [row["profile"] for row in report["runs"]])
        self.assertTrue(all(row["phase"] == "warm" for row in report["runs"]))
        self.assertEqual(.001, report["runs"][0]["edtP95Seconds"])
        self.assertEqual(105, report["runs"][0]["observedRootVisits"])
        self.assertTrue(summary.assess(report)["eligible"])
        self.assertEqual(1.25, summary.assess(report)["speedup"])

    def test_whole_game_maps_visits_and_actual_cancel_metric(self):
        report = self.report("whole-game")
        self.assertEqual(2, report["positions"])
        self.assertEqual([101, 102], report["runs"][0]["rootVisitsByTurn"])
        self.assertEqual(.01, report["runs"][0]["responseSeconds"])
        self.assertNotIn("firstResultSeconds", report["runs"][0])
        self.assertNotIn("numSearchThreads", report["candidateParameters"])
        self.mutate_raw("whole-game", "cancellationMethod", "json-terminate-all-final-responses")
        with self.assertRaisesRegex(ValueError, "cancellation"):
            self.report("whole-game")

    def test_fingerprint_preserves_budget_pv_and_logging_not_locations(self):
        semantics = self.report()["fingerprint"]["commandSemantics"]
        self.assertEqual("100", semantics["analysisPVLen"])
        self.assertEqual("100", semantics["maxVisits"])
        self.assertEqual("false", semantics["logSearchInfo"])
        self.assertEqual("true", semantics["logToStderr"])
        self.assertFalse((summary.TUNING_KEYS | summary.LOCATION_KEYS) & semantics.keys())
        self.assertEqual([], self.report()["fingerprint"]["configIncludes"])

    def test_actual_process_argv_takes_precedence_over_display_command(self):
        raw = self.samples[4]
        actual = summary.split_windows_command(raw["effectiveCommand"])
        self.mutate_raw("realtime", "effectiveCommandArgs", actual)
        self.mutate_raw("realtime", "effectiveCommand", "display string is not a command")
        self.assertEqual(16, self.report()["candidateParameters"]["numSearchThreads"])

    def test_empty_argv_falls_back_to_observed_os_command_line(self):
        self.mutate_raw("realtime", "effectiveCommandArgs", [])
        self.assertEqual(16, self.report()["candidateParameters"]["numSearchThreads"])

    def test_malformed_argv_never_falls_back(self):
        self.mutate_raw("realtime", "effectiveCommandArgs", [None])
        with self.assertRaisesRegex(ValueError, "Invalid effectiveCommandArgs"):
            self.report()

    def test_legacy_probe_cannot_export_even_without_invalid_reason(self):
        self.mutate_raw("realtime", "measurementContractVersion", 1)
        with self.assertRaisesRegex(ValueError, "contract version 2"):
            self.report()
        self.mutate_raw("realtime", "measurementContractVersion", None)
        with self.assertRaisesRegex(ValueError, "contract version 2"):
            self.report()

    def test_windows_command_round_trip(self):
        tokens = [r"C:\a b\katago.exe", "gtp", "-model", r"C:\中文\model.gz",
                  "", 'embedded"quote', "a b\\", "x\\\\y", "trailing\\", "next"]
        self.assertEqual(tokens, summary.split_windows_command(subprocess.list2cmdline(tokens)))
        with self.assertRaisesRegex(ValueError, "Unclosed"):
            summary.split_windows_command('"not closed')

    def test_override_last_wins_and_unsupported_options_fail(self):
        command = ["katago", "gtp", "-model", "model", "-config", "cfg", "-override-config",
                   "analysisPVLen=10,logDir=old", "--override-config", "analysisPVLen=100,logDir=new"]
        self.assertEqual({"analysisPVLen": "100"}, summary.command_details(command)["semantics"])
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            summary.command_details(command + ["--unknown", "value"])

    def test_visible_count_uses_max_and_never_counts_hidden_as_katago(self):
        evidence = self.load()
        gpu = evidence["loaded"][0]["gpu"]
        self.assertEqual(1, gpu["maxEngineProcesses"])
        self.assertEqual(3, gpu["hiddenProcessObservations"])
        self.assertEqual(1, gpu["maxSampleGapSeconds"])
        self.assertEqual(4096, self.report()["runs"][0]["maxMemoryMiB"])

    def test_nearest_rank_is_not_interpolated(self):
        self.assertEqual(19, summary.percentile95(list(range(1, 21))))
        self.assertEqual(.2, summary.percentile95([.1, .2]))

    def test_missing_and_duplicate_warm_pairs_rejected(self):
        self.samples.pop(4)
        self.flush()
        with self.assertRaisesRegex(ValueError, "Incomplete warm"):
            self.report()
        self.samples.append(self.samples[0])
        self.flush()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.load()

    def test_wrong_order_is_not_sorted_away(self):
        self.samples[4], self.samples[6] = self.samples[6], self.samples[4]
        self.flush()
        with self.assertRaisesRegex(ValueError, "nonalternating"):
            self.report()

    def test_wrong_phase_and_mismatched_result_fail(self):
        self.samples[4]["phase"] = "cold-process-isolated-cache"
        self.flush()
        with self.assertRaisesRegex(ValueError, "phase"):
            self.load()
        self.samples[4]["phase"] = "warm-process"
        self.flush()
        sample = self.samples[4]
        path = self.evidence / f"01-candidate-realtime" / "result.json"
        self.write(path, dict(sample, seconds=7))
        with self.assertRaisesRegex(ValueError, "disagree"):
            self.load()

    def test_no_edt_or_empty_or_nonfinite_is_not_manufactured(self):
        for value in ([], [float("nan")], [float("inf")], [-.1]):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    summary.percentile95(value)
        self.mutate_raw("realtime", "edtSamples", 0)
        with self.assertRaisesRegex(ValueError, "EDT sample count"):
            self.report()

    def test_incomplete_visits_export_truth_but_fail_gates(self):
        self.mutate_raw("whole-game", "observedVisits", [101, 99])
        report = self.report("whole-game")
        self.assertIn("Incomplete analysis budget", summary.assess(report)["reasons"])
        self.mutate_raw("whole-game", "observedVisits", [101])
        with self.assertRaisesRegex(ValueError, "per-position"):
            self.report("whole-game")

    def test_no_final_command_or_changed_search_semantics_rejected(self):
        command = self.samples[4]["effectiveCommand"]
        self.mutate_raw("realtime", "effectiveCommand", command.replace("analysisPVLen=100", "analysisPVLen=50"))
        with self.assertRaisesRegex(ValueError, "semantics"):
            self.report()
        self.mutate_raw("realtime", "effectiveCommand", "")
        with self.assertRaisesRegex(ValueError, "effective command"):
            self.report()

    def test_config_includes_and_changed_archived_config_rejected(self):
        (self.evidence / "input.cfg").write_bytes(b"@include other.cfg\n")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.report()
        self.manifest["configSha256"] = hashlib.sha256(b"@include other.cfg\n").hexdigest()
        self.flush()
        with self.assertRaisesRegex(ValueError, "include contents"):
            self.report()

    def test_gpu_failure_multi_gpu_and_truncated_record_rejected(self):
        path = self.evidence / "01-candidate-realtime" / "gpu.jsonl"
        original = path.read_text(encoding="utf-8")
        for changed in (original.replace('"gpuExitCode": 0', '"gpuExitCode": 1'),
                        original.replace('32000, 70"', '32000, 70\\nGPU2, 1, 1000, 2000, 10"'),
                        original + '\n{"incomplete":'):
            path.write_text(changed, encoding="utf-8")
            with self.subTest(changed=changed[-20:]):
                with self.assertRaises(ValueError):
                    self.report()

    def test_overlapping_process_windows_rejected(self):
        path = self.evidence / "01-baseline-realtime" / "gpu.jsonl"
        text = path.read_text(encoding="utf-8")
        rows = [json.loads(line) for line in text.splitlines()]
        for index, row in enumerate(rows):
            row["monotonicSeconds"] = 81 + index
        path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "overlap"):
            self.report()

    def test_raw_app_failure_cannot_be_hidden_by_result_pass(self):
        path = self.evidence / "01-candidate-realtime" / "app-result.json"
        self.write(path, {"status": "FAIL"})
        with self.assertRaisesRegex(ValueError, "mismatch"):
            self.report()

    def test_numerical_gates_reject_slow_pair_noise_latency_and_memory(self):
        original = self.report()
        for key, value, reason in (("seconds", 11, "Not every"),
                                   ("firstResultSeconds", 1, "tail regressed"),
                                   ("responseSeconds", 1, "tail regressed"),
                                   ("edtP95Seconds", 1, "tail regressed"),
                                   ("maxMemoryMiB", 30000, "GPU memory regressed"),
                                   ("maxEngineProcesses", 2, "not one")):
            report = copy.deepcopy(original)
            report["runs"][0][key] = value
            with self.subTest(key=key):
                result = summary.assess(report)
                self.assertFalse(result["eligible"])
                self.assertTrue(any(reason in item for item in result["reasons"]))
        report = copy.deepcopy(original)
        report["runs"][0]["seconds"] = 1
        self.assertIn("Timing variation requires five rounds", summary.assess(report)["reasons"])

    def test_markdown_keeps_cold_warm_and_scope_limits(self):
        evidence = self.load()
        text = summary.markdown(evidence, {"realtime": self.report()}, {}, "Synthetic test, not results.")
        for phrase in ("Cold process", "Warm process", "permission-denied", "not the current daily",
                       "whole-game", "process exit", "1.2500x", "timing CV", "Synthetic test"):
            self.assertIn(phrase, text)

    def test_cli_invalid_evidence_never_creates_import_json(self):
        output = self.root / "exports"
        with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
            code = summary.main([str(self.evidence), "--invalid-reason", "Known async restore defect",
                                 "--export-dir", str(output)])
        self.assertEqual(2, code)
        self.assertFalse(output.exists())

    def test_cli_new_outputs_only_and_never_in_input_directory(self):
        output = self.root / "exports"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, summary.main([str(self.evidence), "--export-dir", str(output)]))
        self.assertTrue((output / "live.json").is_file())
        with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
            self.assertEqual(2, summary.main([str(self.evidence), "--export-dir", str(output)]))
            self.assertEqual(2, summary.main([str(self.evidence), "--markdown", str(self.evidence / "summary.md")]))
        self.assertFalse((self.evidence / "summary.md").exists())

    def test_engine_only_cannot_export_application_or_fake_edt(self):
        evidence = self.load()
        evidence["manifest"]["mode"] = "engine"
        with self.assertRaisesRegex(ValueError, "Engine-only"):
            summary.export_report(evidence, "realtime")

    def test_five_round_numerical_noise_gate_and_every_pair_retained(self):
        report = self.report()
        extra = copy.deepcopy(report["runs"][2:6])
        for row in extra:
            row["round"] += 2
        report["runs"].extend(extra)
        self.assertTrue(summary.assess(report)["eligible"])
        self.assertEqual(5, len(summary.assess(report)["pairedRatios"]))
        report["runs"][0]["seconds"] = 1
        self.assertIn("Five-round timing variation exceeds 15%", summary.assess(report)["reasons"])

    def test_duplicate_json_keys_are_rejected(self):
        path = self.root / "duplicate.json"
        path.write_text('{"status":"FAIL","status":"PASS"}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
            summary.read_json(path)


if __name__ == "__main__":
    unittest.main()
