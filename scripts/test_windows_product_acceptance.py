#!/usr/bin/env python3
"""Focused Windows final-product acceptance runner fixtures."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from scripts import release_asset_provenance as provenance
from scripts import release_asset_topology as topology


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "windows_product_acceptance.ps1"
DATE_TAG = "2026-09-18"
RELEASE_TAG = f"next-{DATE_TAG}.1"
TARGET_SHA = "a" * 40
RUN_ID = 90210
RUN_ATTEMPT = 1


def windows_path(path: Path) -> str:
    resolved = path.resolve()
    text = resolved.as_posix()
    if text.startswith("/mnt/") and len(text) > 7:
        drive = text[5].upper()
        return f"{drive}:\\{text[7:].replace('/', '\\')}"
    result = subprocess.run(
        ["wslpath", "-w", str(resolved)],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@unittest.skipUnless(shutil.which("pwsh.exe") and shutil.which("powershell.exe") and Path("/mnt/c/Temp").is_dir(), "requires WSL with Windows PowerShell")
class WindowsProductAcceptanceFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.class_root = Path(tempfile.mkdtemp(prefix="lizzie-product-acceptance-tools-", dir="/mnt/c/Temp"))
        cls.launcher = cls.class_root / "fixture-launcher.exe"
        cls.sleepy_launcher = cls.class_root / "fixture-sleepy-launcher.exe"
        cls.java = cls.class_root / "fixture-java.exe"
        cls.malicious_java = cls.class_root / "fixture-malicious-java.exe"
        cls.argv_recorder = cls.class_root / "fixture-argv-recorder.exe"
        cls.jvm = Path("/mnt/c/Windows/System32/version.dll")
        ready_source = r'''
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
namespace FixtureLauncher {
  public static class Program {
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr LoadLibrary(string path);
    [STAThread] public static void Main() {
      string root = AppContext.BaseDirectory;
      if (LoadLibrary(Path.Combine(root, "runtime", "bin", "server", "jvm.dll")) == IntPtr.Zero) throw new InvalidOperationException("fixture jvm load failed");
      string data = Path.Combine(root, "user-data");
      string cfg = Path.Combine(root, "app", "LizzieYzy Next.cfg");
      if (File.Exists(cfg)) {
        foreach (string line in File.ReadAllLines(cfg)) {
          const string prefix = "java-options=-Dlizzie.work.dir=";
          if (line.StartsWith(prefix, StringComparison.Ordinal)) data = line.Substring(prefix.Length);
        }
      }
      Directory.CreateDirectory(Path.Combine(data, "logs"));
      File.WriteAllText(Path.Combine(data, "config.txt"), "{\"ui\":{},\"leelaz\":{\"engine-settings-list\":[]}}\n");
      File.WriteAllText(Path.Combine(data, "persist"), "fixture\n");
      File.WriteAllText(Path.Combine(data, "logs", "app.log"), "application ready\n");
      Application.Run(new Form { Text = "LizzieYzy Next acceptance fixture", Width = 320, Height = 180 });
    }
  }
}'''
        sleepy_source = (
            "using System.Threading; namespace FixtureSleepy { "
            "public static class Program { [System.STAThread] public static void Main() { Thread.Sleep(30000); } } }"
        )
        java_source = (
            "using System; namespace FixtureJava { public static class Program { "
            "public static void Main() { Console.Error.WriteLine(\"openjdk version 21 fixture 64-Bit x86_64\"); } } }"
        )
        malicious_source = (
            "using System; using System.IO; namespace FixtureMaliciousJava { public static class Program { "
            "public static void Main() { File.WriteAllText(Path.Combine(AppContext.BaseDirectory, \"executed.marker\"), \"executed\"); } } }"
        )
        argv_source = (
            "using System; using System.IO; namespace FixtureArgv { public static class Program { "
            "public static void Main(string[] args) { File.WriteAllLines(Environment.GetEnvironmentVariable(\"LIZZIE_ACCEPTANCE_ARGV_OUT\"), args); } } }"
        )
        commands = (
            f"Add-Type -TypeDefinition @'\n{ready_source}\n'@ -ReferencedAssemblies System.Windows.Forms -OutputAssembly '{windows_path(cls.launcher)}' -OutputType WindowsApplication; "
            f"Add-Type -TypeDefinition @'\n{sleepy_source}\n'@ -OutputAssembly '{windows_path(cls.sleepy_launcher)}' -OutputType WindowsApplication; "
            f"Add-Type -TypeDefinition @'\n{java_source}\n'@ -OutputAssembly '{windows_path(cls.java)}' -OutputType ConsoleApplication; "
            f"Add-Type -TypeDefinition @'\n{malicious_source}\n'@ -OutputAssembly '{windows_path(cls.malicious_java)}' -OutputType ConsoleApplication; "
            f"Add-Type -TypeDefinition @'\n{argv_source}\n'@ -OutputAssembly '{windows_path(cls.argv_recorder)}' -OutputType ConsoleApplication"
        )
        result = subprocess.run(
            ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", commands],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls.class_root, ignore_errors=True)

    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="lizzie-product-acceptance-", dir="/mnt/c/Temp"))
        self.addCleanup(shutil.rmtree, self.root, True)

    def run_script(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "pwsh.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                windows_path(SCRIPT),
                *arguments,
            ],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=45,
        )

    def run_driver(self, driver: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(driver)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=45,
        )

    def write_provenance(self, asset: Path) -> Path:
        records = []
        for name in topology.provenance_names("windows", DATE_TAG):
            if name == asset.name:
                records.append({"name": name, "sizeBytes": asset.stat().st_size, "sha256": sha256(asset)})
            else:
                records.append({"name": name, "sizeBytes": 1, "sha256": "0" * 64})
        manifest = self.root / f"{asset.stem}-provenance.json"
        manifest.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "platform": "windows",
                    "dateTag": DATE_TAG,
                    "releaseTag": RELEASE_TAG,
                    "targetSha": TARGET_SHA,
                    "workflowRunId": RUN_ID,
                    "workflowRunAttempt": RUN_ATTEMPT,
                    "assets": records,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        return manifest

    def create_portable(self, *, unsafe: bool = False, ready: bool = True) -> tuple[Path, Path]:
        asset = self.root / f"{DATE_TAG}-windows64.with-katago.portable.zip"
        product = "LizzieYzy Next"
        files: dict[str, bytes] = {
            f"{product}/.lizzie-portable": b"portable fixture\n",
            f"{product}/LizzieYzy Next.exe": (self.launcher if ready else self.sleepy_launcher).read_bytes(),
            f"{product}/runtime/bin/java.exe": self.java.read_bytes(),
            f"{product}/runtime/bin/server/jvm.dll": self.jvm.read_bytes(),
            f"{product}/app/LizzieYzy Next.cfg": b"[Application]\napp.mainjar=lizzie-yzy2.5.3-shaded.jar\n",
            f"{product}/app/lizzie-yzy2.5.3-shaded.jar": b"fixture-shaded-jar",
            f"{product}/app/lizzieyzy-next-installed-manifest.json": (
                json.dumps({"schemaVersion": 1, "releaseTag": RELEASE_TAG, "platform": "windows", "flavor": "with-katago"}) + "\n"
            ).encode(),
            f"{product}/app/engines/katago/configs/gtp.cfg": b"fixture config",
            f"{product}/app/engines/katago/windows-x64/katago.exe": b"fixture engine",
            f"{product}/app/engines/katago/windows-x64/lizzieyzy-next-engine-backend.txt": b"cpu\n",
            f"{product}/app/weights/default.bin.gz": b"fixture weight",
            f"{product}/app/jcef-bundle/libcef.dll": b"fixture jcef",
            f"{product}/app/jcef-bundle/lizzieyzy-next-jcef-manifest.txt": b"fixture jcef manifest",
            f"{product}/app/readboard/readboard.exe": b"fixture readboard",
            f"{product}/app/readboard/lizzieyzy-next-readboard-manifest.txt": b"fixture readboard manifest",
        }
        with zipfile.ZipFile(asset, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"{product}/user-data/", b"")
            for name, content in files.items():
                archive.writestr(name, content)
            if unsafe:
                archive.writestr("../escape.txt", b"escape")
        return asset, self.write_provenance(asset)

    def create_core_update(self, *, replace_runtime: bool = False, noop_config: bool = False) -> tuple[Path, Path]:
        asset = self.root / f"{DATE_TAG}-windows64.core-update.zip"
        payloads = {
            "app/lizzie-yzy2.5.3-shaded.jar": b"candidate core jar",
            "app/LizzieYzy Next.cfg": b"candidate launcher cfg",
            "lizzieyzy-next-core.jar": b"candidate core jar",
        }
        if noop_config:
            payloads["app/LizzieYzy Next.cfg"] = b"[Application]\napp.mainjar=lizzie-yzy2.5.3-shaded.jar\n"
        if replace_runtime:
            payloads["runtime/acceptance-sentinel.bin"] = b"forbidden replacement"
        files = [
            {
                "path": name,
                "purpose": "fixture",
                "sizeBytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }
            for name, content in payloads.items()
        ]
        manifest = {
            "schemaVersion": 1,
            "kind": "windows-core-update",
            "releaseTag": RELEASE_TAG,
            "dateTag": DATE_TAG,
            "manualOverlayTarget": "fixture",
            "preserves": ["user-data/", "runtime/", "app/engines/", "app/weights/", "app/jcef-bundle/", "app/readboard/"],
            "files": files,
        }
        with zipfile.ZipFile(asset, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in payloads.items():
                archive.writestr(name, content)
            archive.writestr("README.txt", b"fixture core update")
            archive.writestr("lizzieyzy-next-core-update-manifest.json", json.dumps(manifest).encode())
        return asset, self.write_provenance(asset)

    def prepare(self, asset: Path, manifest: Path, name: str) -> tuple[Path, subprocess.CompletedProcess[str]]:
        evidence = self.root / name
        result = self.run_script(
            "-Command", "Prepare",
            "-AssetFile", windows_path(asset),
            "-ProvenanceFile", windows_path(manifest),
            "-Platform", "windows",
            "-DateTag", DATE_TAG,
            "-ReleaseTag", RELEASE_TAG,
            "-TargetSha", TARGET_SHA,
            "-RunId", str(RUN_ID),
            "-RunAttempt", str(RUN_ATTEMPT),
            "-EvidenceDir", windows_path(evidence),
        )
        return evidence, result

    def test_prepares_verified_portable_with_unicode_local_identity(self) -> None:
        asset, manifest = self.create_portable()
        evidence, result = self.prepare(asset, manifest, "portable evidence")
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        candidate = json.loads((evidence / "candidate.json").read_text(encoding="utf-8"))
        prepared = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        self.assertEqual("windows_portable", candidate["artifact"]["key"])
        self.assertEqual(sha256(asset), candidate["artifact"]["sha256"])
        self.assertIn("验收", prepared["productRoot"])
        self.assertEqual("portable-product", prepared["artifactClass"])
        self.assertEqual("cpu", prepared["layout"]["backend"])
        self.assertTrue((Path("/mnt/c") / Path(prepared["productRoot"][3:].replace("\\", "/"))).exists())

    def test_rejects_traversal_before_extracting_product(self) -> None:
        asset, manifest = self.create_portable(unsafe=True)
        evidence, result = self.prepare(asset, manifest, "unsafe evidence")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsafe archive entry", result.stderr + result.stdout)
        self.assertFalse((evidence / "prepared.json").exists())
        self.assertFalse((self.root / "escape.txt").exists())

    def test_rejects_tampered_candidate_before_start_mutation(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "tamper evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        with asset.open("ab") as handle:
            handle.write(b"tampered")
        result = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
            "-WaitSeconds", "2",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertRegex((result.stderr + result.stdout).lower(), r"(size|hash) drift")
        self.assertFalse((evidence / "run.json").exists())

    def test_rejects_runtime_drift_without_executing_it(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "runtime drift evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        identity = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        runtime = Path("/mnt/c") / Path(identity["layout"]["runtime"][3:].replace("\\", "/"))
        shutil.copy2(self.malicious_java, runtime)
        result = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtime drift", (result.stderr + result.stdout).lower())
        self.assertFalse((runtime.parent / "executed.marker").exists())

    def test_rejects_component_closure_drift_before_start(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "component drift evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        identity = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        product_root = Path("/mnt/c") / Path(identity["productRoot"][3:].replace("\\", "/"))
        (product_root / "app" / "jcef-bundle" / "injected.dll").write_bytes(b"drift")
        result = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("jcef closure drift", (result.stderr + result.stdout).lower())
        self.assertFalse((evidence / "run.json").exists())

    def test_missing_readiness_times_out_and_cleans_owned_process(self) -> None:
        asset, manifest = self.create_portable(ready=False)
        evidence, prepared = self.prepare(asset, manifest, "timeout evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        identity = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        launcher = str(identity["layout"]["launcher"])
        result = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
            "-WaitSeconds", "2",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Timed out waiting", result.stderr + result.stdout)
        probe = subprocess.run(
            [
                "pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                f"@(Get-CimInstance Win32_Process | Where-Object {{ $_.ExecutablePath -ieq '{launcher}' }}).Count",
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertEqual("0", probe.stdout.strip())
        self.assertFalse((evidence / "run.json").exists())
        cleanup = json.loads((evidence / "startup-cleanup.json").read_text(encoding="utf-8"))
        self.assertTrue(cleanup["complete"])
        self.assertEqual([], cleanup["remainingOwnedPids"])

    def test_core_update_preserves_resources_and_writes_valid_acceptance(self) -> None:
        portable, portable_manifest = self.create_portable()
        prior_evidence, prior_result = self.prepare(portable, portable_manifest, "prior evidence")
        self.assertEqual(0, prior_result.returncode, prior_result.stderr or prior_result.stdout)
        core, core_manifest = self.create_core_update()
        core_evidence, core_result = self.prepare(core, core_manifest, "core evidence")
        self.assertEqual(0, core_result.returncode, core_result.stderr or core_result.stdout)
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(core_evidence / "candidate.json"),
            "-PriorCandidateJson", windows_path(prior_evidence / "candidate.json"),
            "-Scenario", "core-update-preserve",
            "-EvidenceDir", windows_path(core_evidence),
        )
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        record = json.loads((core_evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("PASS", record["status"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertIsInstance(record["observed"]["launcher"]["pid"], int)
        self.assertIn("launched the candidate release", record["observed"]["outcome"]["summary"])
        run_record = json.loads((core_evidence / "run.json").read_text(encoding="utf-8"))
        self.assertEqual("STOPPED", run_record["state"])
        self.assertTrue(run_record["cleanup"]["complete"])
        self.assertEqual("PASS", record["observed"]["outcome"]["distributionStatus"])
        self.assertEqual("NOT_REQUIRED", record["observed"]["outcome"]["inferenceStatus"])
        self.assertEqual(record["expected"]["product"]["jar"]["sha256"], record["observed"]["jar"]["sha256"])
        self.assertEqual("STOPPED", record["observed"]["stop"]["state"])

    def test_one_shot_startup_failure_records_shared_data_drift(self) -> None:
        portable, portable_manifest = self.create_portable(ready=False)
        prior_evidence, prior_result = self.prepare(portable, portable_manifest, "prior timeout evidence")
        self.assertEqual(0, prior_result.returncode, prior_result.stderr or prior_result.stdout)
        core, core_manifest = self.create_core_update()
        evidence, core_result = self.prepare(core, core_manifest, "one shot timeout evidence")
        self.assertEqual(0, core_result.returncode, core_result.stderr or core_result.stdout)
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        driver = self.root / "one-shot-cleanup-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            "$script:snapshotCalls = 0\n"
            "function Get-SharedDataSnapshot { $script:snapshotCalls++; return [ordered]@{ fixture = if ($script:snapshotCalls -eq 1) { 'before' } else { 'after' } } }\n"
            f"$CandidateJson = '{windows_path(evidence / 'candidate.json')}'\n"
            f"$PriorCandidateJson = '{windows_path(prior_evidence / 'candidate.json')}'\n"
            f"$EvidenceDir = '{windows_path(evidence)}'\n"
            "$Scenario = 'core-update-preserve'\n"
            "$WaitSeconds = 2\n"
            "try { Invoke-Run; throw 'expected one-shot startup failure' } catch { if ($_.Exception.Message -match 'expected one-shot') { throw } }\n",
            encoding="utf-8",
        )
        result = self.run_driver(driver)
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("launch", record["phase"])
        self.assertFalse(record["cleanup"]["complete"])
        self.assertIn("shared/profile data changed", record["cleanup"]["remainingOwnedResources"])
        cleanup_path = evidence / "startup-cleanup.json"
        cleanup = json.loads(cleanup_path.read_text(encoding="utf-8"))
        self.assertFalse(cleanup["sharedDataUnchanged"])
        self.assertIn(windows_path(cleanup_path), record["failure"]["diagnostics"])

    def test_core_update_failure_writes_strict_acceptance_record(self) -> None:
        core, core_manifest = self.create_core_update()
        evidence, core_result = self.prepare(core, core_manifest, "failed core evidence")
        self.assertEqual(0, core_result.returncode, core_result.stderr or core_result.stdout)
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-PriorCandidateJson", windows_path(evidence / "missing-prior.json"),
            "-Scenario", "core-update-preserve",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("launch", record["blockedPhase"])
        self.assertIsNone(record["failure"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_rejects_core_manifest_that_replaces_preserved_runtime(self) -> None:
        core, manifest = self.create_core_update(replace_runtime=True)
        evidence, result = self.prepare(core, manifest, "bad core evidence")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("replace preserved resource", result.stderr + result.stdout)
        self.assertFalse((evidence / "prepared.json").exists())

    def test_missing_candidate_writes_strict_blocked_record(self) -> None:
        evidence = self.root / "missing transfer evidence"
        evidence.mkdir()
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(evidence / "missing-candidate.json"),
            "-TargetSha", TARGET_SHA,
            "-ExpectedArtifactKey", "windows_portable",
            "-ExpectedArtifactName", f"{DATE_TAG}-windows64.with-katago.portable.zip",
            "-ExpectedArtifactClass", "portable-product",
            "-Scenario", "variant-launch",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("identity", record["blockedPhase"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_missing_config_writes_strict_fail_record(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "missing config evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        identity = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        config = Path("/mnt/c") / Path(identity["layout"]["config"][3:].replace("\\", "/"))
        config.unlink()
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "variant-launch",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("identity", record["phase"])

    def test_core_preservation_mismatch_fails_and_removes_overlay(self) -> None:
        portable, portable_manifest = self.create_portable()
        prior_evidence, prior_result = self.prepare(portable, portable_manifest, "prior mismatch evidence")
        self.assertEqual(0, prior_result.returncode, prior_result.stderr or prior_result.stdout)
        core, core_manifest = self.create_core_update(noop_config=True)
        evidence, core_result = self.prepare(core, core_manifest, "core mismatch evidence")
        self.assertEqual(0, core_result.returncode, core_result.stderr or core_result.stdout)
        protected = evidence / "core update target 验收"
        protected.mkdir()
        sentinel = protected / "pre-existing.txt"
        sentinel.write_text("do not remove", encoding="utf-8")
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-PriorCandidateJson", windows_path(prior_evidence / "candidate.json"),
            "-Scenario", "core-update-preserve",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("do not remove", sentinel.read_text(encoding="utf-8"))
        self.assertEqual([], list(evidence.glob("core update target 验收-*")))

    def test_stop_cleans_after_launcher_already_exited(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "dead launcher evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        started = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
            "-WaitSeconds", "10",
        )
        self.assertEqual(0, started.returncode, started.stderr or started.stdout)
        run = json.loads((evidence / "run.json").read_text(encoding="utf-8"))
        subprocess.run(
            ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", f"Stop-Process -Id {run['launcher']['pid']} -Force"],
            check=True,
            capture_output=True,
            text=True,
        )
        stopped = self.run_script("-Command", "Stop", "-RunJson", windows_path(evidence / "run.json"))
        self.assertEqual(0, stopped.returncode, stopped.stderr or stopped.stdout)
        terminal = json.loads((evidence / "run.json").read_text(encoding="utf-8"))
        self.assertEqual("STOPPED", terminal["state"])
        self.assertTrue(terminal["cleanup"]["complete"])

    def test_stop_records_identity_drift_but_still_cleans(self) -> None:
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "stop drift evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        started = self.run_script(
            "-Command", "Start",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "live-session",
            "-EvidenceDir", windows_path(evidence),
            "-WaitSeconds", "10",
        )
        self.assertEqual(0, started.returncode, started.stderr or started.stdout)
        identity = json.loads((evidence / "prepared.json").read_text(encoding="utf-8"))
        product_root = Path("/mnt/c") / Path(identity["productRoot"][3:].replace("\\", "/"))
        (product_root / "app" / "jcef-bundle" / "drift.txt").write_text("drift", encoding="utf-8")
        stopped = self.run_script("-Command", "Stop", "-RunJson", windows_path(evidence / "run.json"))
        self.assertNotEqual(0, stopped.returncode)
        terminal = json.loads((evidence / "run.json").read_text(encoding="utf-8"))
        self.assertEqual("STOPPED", terminal["state"])
        self.assertTrue(terminal["cleanup"]["complete"])
        self.assertTrue(terminal["cleanup"]["identityErrors"])
        self.assertEqual([], terminal["cleanup"]["remainingOwnedPids"])

    def test_stop_does_not_kill_reused_pid_incarnation(self) -> None:
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        product_root = windows_path(self.root / "pid reuse product")
        driver = self.root / "pid-reuse-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            "$script:killed = @()\n"
            "function Assert-LiveRunIdentity { throw 'identity drift fixture' }\n"
            "function Get-OwnedProcessTree { return @(4242) }\n"
            f"function Get-ProcessSnapshot {{ param([int[]]$ProcessIds); if ($ProcessIds.Count -eq 0) {{ return @() }}; return @([pscustomobject]@{{ pid=4242; parentPid=1; image='{product_root}\\launcher.exe'; commandLine='new'; creationDate='NEW' }}) }}\n"
            "function Get-Process { return $null }\n"
            "function Stop-Process { param([int]$Id); $script:killed += $Id }\n"
            "function Remove-FirewallBoundary { param([string[]]$Rules) }\n"
            "function Get-RemainingFirewallRules { return @() }\n"
            "function Restore-ConfigBytes { param([string]$ConfigPath,[string]$Base64) }\n"
            "function Get-SharedDataSnapshot { return [ordered]@{ fixture='same' } }\n"
            "function Write-JsonAtomic { param([string]$Path,[object]$Value) }\n"
            f"$run = [pscustomobject]@{{ schemaVersion=1; state='RUNNING'; productRoot='{product_root}'; launcher=[pscustomobject]@{{ pid=4242; process=[pscustomobject]@{{ pid=4242; image='{product_root}\\launcher.exe'; creationDate='OLD' }} }}; ownedPids=@(4242); processes=@([pscustomobject]@{{ pid=4242; image='{product_root}\\launcher.exe'; creationDate='OLD' }}); network=[pscustomobject]@{{ offline=$false; firewallRules=@() }}; configRestore=[pscustomobject]@{{ path=$null; bytesBase64=$null }}; sharedDataBefore=[ordered]@{{ fixture='same' }}; candidate=[pscustomobject]@{{ class='portable-product' }}; cleanup=[pscustomobject]@{{ complete=$false; remainingOwnedPids=@(); firewallRulesRemaining=@(); auditPolicyRestored=$true; configRestored=$true; sharedDataUnchanged=$null; uninstallComplete=$null; identityErrors=@(); errors=@() }} }}\n"
            "try { Stop-OwnedRun -Run $run -Path 'fixture-run.json' } catch {}\n"
            "if ($script:killed.Count -ne 0) { throw 'replacement process was killed' }\n",
            encoding="utf-8",
        )
        result = self.run_driver(driver)
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)

    def test_deny_events_require_full_packaged_executable_identity(self) -> None:
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        product_root = self.root / "event identity product"
        packaged = product_root / "runtime" / "bin" / "java.exe"
        packaged.parent.mkdir(parents=True)
        packaged.write_bytes(b"fixture")
        outside = self.root / "outside" / "java.exe"
        outside.parent.mkdir()
        outside.write_bytes(b"fixture")
        driver = self.root / "event-identity-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            "function New-FixtureEvent([long]$RecordId,[string]$Application) { $event = [pscustomobject]@{ RecordId=$RecordId; TimeCreated=[datetime]::UtcNow; Application=$Application }; $event | Add-Member -MemberType ScriptMethod -Name ToXml -Value { '<Event><EventData><Data Name=\"ProcessID\">4242</Data><Data Name=\"Application\">' + $this.Application + '</Data><Data Name=\"DestAddress\">8.8.8.8</Data><Data Name=\"DestPort\">443</Data></EventData></Event>' }; return $event }\n"
            f"function Get-SecurityDenyEvents {{ return @((New-FixtureEvent -RecordId 1 -Application '{windows_path(packaged)}'), (New-FixtureEvent -RecordId 2 -Application '{windows_path(outside)}')) }}\n"
            f"$events = @(Get-BlockedConnectionEvents -AfterRecordId 0 -ProductRoot '{windows_path(product_root)}')\n"
            f"if ($events.Count -ne 1 -or $events[0].application -ine '{windows_path(packaged)}') {{ throw 'deny-event ownership mismatch' }}\n",
            encoding="utf-8",
        )
        result = self.run_driver(driver)
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)

    def test_non_admin_after_extraction_writes_launch_blocked_record(self) -> None:
        admin = subprocess.run(
            [
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)",
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        ).stdout.strip().lower()
        if admin == "true":
            self.skipTest("fixture requires a non-administrator Windows session")
        asset, manifest = self.create_portable()
        evidence, prepared = self.prepare(asset, manifest, "blocked after extraction evidence")
        self.assertEqual(0, prepared.returncode, prepared.stderr or prepared.stdout)
        result = self.run_script(
            "-Command", "Run",
            "-CandidateJson", windows_path(evidence / "candidate.json"),
            "-Scenario", "variant-launch",
            "-EvidenceDir", windows_path(evidence),
        )
        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("launch", record["blockedPhase"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertFalse((evidence / "run.json").exists())

    def test_failed_installer_rolls_back_discovered_owned_registration(self) -> None:
        marker = self.root / "rollback marker.txt"
        install_root = self.root / "installed product 验收"
        log_path = self.root / "failed install.log"
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        script = windows_path(fixture_script).replace("'", "''")
        command = rf'''
. '{script}' -Command Prepare
$script:fixtureEntry = $null
$script:rolledBack = $false
function Assert-Administrator {{ param([string]$Operation) }}
function Get-UninstallEntries {{ if ($script:fixtureEntry) {{ return @($script:fixtureEntry) }} return @() }}
function Invoke-InstallerProcess {{
  param([string]$Installer, [string]$InstallRoot, [string]$LogPath)
  $script:fixtureEntry = [pscustomobject]@{{
    PSPath = 'Registry::fixture-owned'; PSChildName = '{{11111111-1111-1111-1111-111111111111}}';
    DisplayName = 'LizzieYzy Next fixture'; DisplayVersion = '1.0'; UninstallString = 'fixture-uninstall.exe';
    QuietUninstallString = ''; InstallLocation = $InstallRoot
  }}
  return 1603
}}
function Invoke-Uninstall {{
  param([object]$InstallEvidence, [string]$LogPath)
  Set-Content -LiteralPath '{windows_path(marker)}' -Value 'rolled back'
  $script:fixtureEntry = $null
  $script:rolledBack = $true
}}
try {{
  Invoke-Installer -Installer 'fixture-installer.exe' -InstallRoot '{windows_path(install_root)}' -LogPath '{windows_path(log_path)}'
  throw 'expected installer failure'
}}
catch {{
  if ($_.Exception.Message -notmatch '1603') {{ throw }}
}}
if (-not $script:rolledBack -or $script:fixtureEntry) {{ throw 'owned installer rollback did not complete' }}
'''
        driver = self.root / "installer-rollback-fixture.ps1"
        driver.write_text(command, encoding="utf-8")
        result = subprocess.run(
            ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(driver)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        self.assertEqual("rolled back", marker.read_text(encoding="utf-8").strip())

    def test_identity_failure_removes_prepared_installer_ownership(self) -> None:
        evidence = self.root / "installer identity failure evidence"
        evidence.mkdir()
        product_root = evidence / "prepared product 验收"
        product_root.mkdir()
        candidate = evidence / "candidate.json"
        candidate.write_text(
            json.dumps({
                "targetSha": TARGET_SHA,
                "releaseTag": RELEASE_TAG,
                "artifact": {"key": "windows_installer", "name": f"{DATE_TAG}-windows64.with-katago.installer.exe", "class": "installer-product"},
                "provenance": {"path": "fixture-provenance.json", "sha256": "1" * 64},
            }),
            encoding="utf-8",
        )
        registry_path = "Registry::fixture-prepared-installer"
        (evidence / "prepared.json").write_text(
            json.dumps({
                "schemaVersion": 1,
                "candidatePath": windows_path(candidate),
                "artifactClass": "installer-product",
                "productRoot": windows_path(product_root),
                "install": {"RegistryPath": registry_path, "ProductCode": "{11111111-1111-1111-1111-111111111111}"},
            }),
            encoding="utf-8",
        )
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        marker = self.root / "identity-failure-uninstalled.txt"
        driver = self.root / "installer-identity-cleanup-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            f"$script:entry = [pscustomobject]@{{ PSPath='{registry_path}'; PSChildName='{{11111111-1111-1111-1111-111111111111}}'; DisplayName='LizzieYzy Next fixture'; DisplayVersion='1.0'; UninstallString='fixture.exe'; QuietUninstallString=''; InstallLocation='{windows_path(product_root)}' }}\n"
            "function Read-PreparedIdentity { throw 'Prepared launcher drift detected.' }\n"
            "function Get-UninstallEntries { if ($script:entry) { return @($script:entry) }; return @() }\n"
            f"function Invoke-Uninstall {{ param([object]$InstallEvidence,[string]$LogPath); $script:entry = $null; Set-Content -LiteralPath '{windows_path(marker)}' -Value 'removed' }}\n"
            f"$CandidateJson = '{windows_path(candidate)}'\n"
            f"$EvidenceDir = '{windows_path(evidence)}'\n"
            "$Scenario = 'variant-launch'\n"
            "try { Invoke-Run; throw 'expected identity failure' } catch { if ($_.Exception.Message -match 'expected identity') { throw } }\n",
            encoding="utf-8",
        )
        result = self.run_driver(driver)
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertFalse(product_root.exists())
        self.assertEqual("removed", marker.read_text(encoding="utf-8").strip())

    def test_installer_process_preserves_unicode_argument_boundaries(self) -> None:
        output = self.root / "argv output 验收.txt"
        install_root = self.root / "installed product 验收"
        log_path = self.root / "installer log 验收.log"
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        driver = self.root / "installer-argv-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            f"$env:LIZZIE_ACCEPTANCE_ARGV_OUT = '{windows_path(output)}'\n"
            f"$exitCode = Invoke-InstallerProcess -Installer '{windows_path(self.argv_recorder)}' -InstallRoot '{windows_path(install_root)}' -LogPath '{windows_path(log_path)}'\n"
            "if ($exitCode -ne 0) { throw \"argv recorder exited with $exitCode\" }\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(driver)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        self.assertEqual(
            ["/qn", "/norestart", f"INSTALLDIR={windows_path(install_root)}", "/l*v", windows_path(log_path)],
            output.read_text(encoding="utf-8-sig").splitlines(),
        )

    def test_upgrade_guids_are_canonical(self) -> None:
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        driver = self.root / "upgrade-guid-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            "$values = @('windows_installer','windows_opencl_installer','windows_nvidia_installer') | ForEach-Object { Get-ExpectedUpgradeUuid -ArtifactKey $_ }\n"
            "if (@($values | Where-Object { $_ -cnotmatch '^\\{[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}\\}$' }).Count -ne 0) { throw 'non-canonical upgrade GUID' }\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(driver)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)

    def test_rejects_engine_oracle_replayed_from_another_candidate(self) -> None:
        fixture_script = self.root / "windows_product_acceptance.ps1"
        shutil.copy2(SCRIPT, fixture_script)
        oracle = self.root / "replayed oracle.json"
        oracle.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "scenario": "windows-final-product-real-cpu",
                    "status": "PASS",
                    "binding": {
                        "candidateSha256": "1" * 64,
                        "artifactSha256": "2" * 64,
                        "runJsonSha256": "3" * 64,
                        "launcherSha256": "4" * 64,
                        "runtimeSha256": "5" * 64,
                        "jvmModuleSha256": "6" * 64,
                        "jarSha256": "7" * 64,
                        "dataRoot": "C:\\fixture",
                        "launcherPid": 1,
                        "enginePid": 2,
                        "engineCommand": "fixture",
                        "oracleSha256": "8" * 64,
                    },
                    "oracle": {},
                }
            ),
            encoding="utf-8",
        )
        driver = self.root / "oracle-replay-fixture.ps1"
        driver.write_text(
            f". '{windows_path(fixture_script)}' -Command Prepare\n"
            "$run = [pscustomobject]@{ candidate = [pscustomobject]@{ sha256 = 'a'; artifactSha256 = 'b' } }\n"
            f"Assert-EngineOracle -Path '{windows_path(oracle)}' -Run $run -RunPath '{windows_path(self.root / 'missing-run.json')}'\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(driver)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("candidate binding differs", result.stderr + result.stdout)


if __name__ == "__main__":
    unittest.main()
