#!/usr/bin/env python3
"""Guards for login support in the shipped, trimmed Java runtime."""

import argparse
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import package_runtime_tools as runtime


class ChatGptRuntimeTests(unittest.TestCase):
    def test_safe_modules_include_loopback_server_without_jdeps(self):
        self.assertIn("jdk.httpserver", runtime.SAFE_DESKTOP_MODULES)

    def test_probe_uses_packaged_java_and_actual_jar_with_timeout(self):
        result = subprocess.CompletedProcess([], 0, "CHATGPT_LOOPBACK_SMOKE_OK\n", "")
        with patch.object(runtime, "run", return_value=result) as run:
            runtime.verify_chatgpt_runtime(Path("bundled runtime"), Path("app/candidate.jar"))
        command = run.call_args.args[0]
        self.assertEqual(Path("bundled runtime/bin"), Path(command[0]).parent)
        self.assertEqual(["-cp", str(Path("app/candidate.jar")), "featurecat.lizzie.teacher.ChatGptRuntimeSmoke"], command[1:])
        self.assertEqual(30, run.call_args.kwargs["timeout"])

    def test_empty_success_cannot_pass_probe(self):
        with patch.object(runtime, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
            with self.assertRaises(RuntimeError):
                runtime.verify_chatgpt_runtime(Path("runtime"), Path("candidate.jar"))

    def test_failed_or_timed_out_probe_cannot_pass(self):
        for error in (subprocess.CalledProcessError(1, []), subprocess.TimeoutExpired([], 30)):
            with self.subTest(error=type(error).__name__), patch.object(runtime, "run", side_effect=error):
                with self.assertRaises(type(error)):
                    runtime.verify_chatgpt_runtime(Path("runtime"), Path("candidate.jar"))

    def test_broken_fallback_is_rejected_before_replacing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "old-runtime"
            source.mkdir()
            args = argparse.Namespace(fallback_source=str(source), platform="windows-x64", jar="candidate.jar")
            with (
                patch.object(runtime, "host_can_jlink_platform", return_value=True),
                patch.object(runtime, "verify_chatgpt_runtime", side_effect=RuntimeError("missing module")),
                patch.object(runtime, "copy_runtime") as copy,
            ):
                with self.assertRaises(RuntimeError):
                    runtime.runtime_fallback(args, root / "output", root / "manifest.json", "jlink unavailable")
                copy.assert_not_called()


if __name__ == "__main__":
    unittest.main()
