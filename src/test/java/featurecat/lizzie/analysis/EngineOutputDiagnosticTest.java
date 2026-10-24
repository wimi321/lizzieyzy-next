package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineOutputDiagnosticTest {
  @Test
  void explicitWindowsError206KeepsTheDllAndOriginalEvidence() {
    String text = "Could not load library cudnn_engines_precompiled64_9.dll. Error code 206";
    var findings = EngineOutputDiagnostic.parse("stderr", text, Instant.EPOCH);

    assertEquals(1, findings.size());
    var finding = findings.get(0);
    assertEquals("path-too-long", finding.outcome());
    assertEquals("cudnn_engines_precompiled64_9.dll", finding.dll());
    assertEquals("engine-stderr", finding.evidence());
    assertEquals(text, finding.detail());
    assertEquals(Instant.EPOCH, finding.checkedAt());
  }

  @Test
  void explicitPathErrorDoesNotBorrowAnotherClausesCode() {
    var findings =
        EngineOutputDiagnostic.parse(
            "stderr",
            "Could not load library missing.dll. Error code 126; "
                + "Could not load library long.dll. Error code 206",
            Instant.EPOCH);

    assertEquals(2, findings.size());
    assertEquals("dll-not-found", findings.get(0).outcome());
    assertEquals("missing.dll", findings.get(0).dll());
    assertEquals("path-too-long", findings.get(1).outcome());
    assertEquals("long.dll", findings.get(1).dll());
  }

  @Test
  void pathLengthAndUnrelated206NumbersDoNotDiagnoseAWindowsPathError() {
    for (String text :
        List.of(
            "Could not load library foo.dll. Error code 2060",
            "Could not load library foo.dll. After 206 attempts",
            "Could not load library foo.dll: 206",
            "Could not load library foo.dll. Model code 206",
            "Could not load library \"C:/" + "nested/".repeat(50) + "foo.dll\"")) {
      var findings = EngineOutputDiagnostic.parse("stderr", text, Instant.EPOCH);
      assertEquals(1, findings.size(), text);
      assertEquals("dll-load-failed", findings.get(0).outcome(), text);
    }
    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr", "Successfully loaded foo.dll. Error code 206", Instant.EPOCH)
            .isEmpty());
  }

  @Test
  void independentFailureClausesKeepTheirOwnErrorCategory() {
    var findings =
        EngineOutputDiagnostic.parse(
            "stderr",
            "Could not load library first.dll. Error code 126; Could not load library second.dll. Error code 193",
            Instant.EPOCH);
    assertEquals(2, findings.size());
    assertEquals("first.dll", findings.get(0).dll());
    assertEquals("dll-not-found", findings.get(0).outcome());
    assertEquals("second.dll", findings.get(1).dll());
    assertEquals("invalid-image-format", findings.get(1).outcome());
  }

  @Test
  void parsesCudnnError126AsDllNotFound() {
    Instant checkedAt = Instant.parse("2026-09-22T04:00:00Z");
    String text = "Could not load library cudnn64_9.dll. Error code 126";

    List<EngineStartupDiagnostics.Finding> findings =
        EngineOutputDiagnostic.parse("stderr", text, checkedAt);

    assertEquals(1, findings.size());
    EngineStartupDiagnostics.Finding finding = findings.get(0);

    assertEquals("dll-not-found", finding.outcome());
    assertEquals("cudnn64_9.dll", finding.dll());
    assertEquals("engine-stderr", finding.evidence());
    assertEquals("Could not load library cudnn64_9.dll. Error code 126", finding.detail());
    assertEquals(checkedAt, finding.checkedAt());
  }

  @Test
  void parsesWindowsLoaderCannotProceedAndMissingDll() {
    Instant checkedAt = Instant.now();

    List<EngineStartupDiagnostics.Finding> f1 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "The code execution cannot proceed because foo.dll was not found.",
            checkedAt);
    assertEquals(1, f1.size());
    assertEquals("dll-not-found", f1.get(0).outcome());
    assertEquals("foo.dll", f1.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f2 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "The program can't start because bar.dll is missing from your computer.",
            checkedAt);
    assertEquals(1, f2.size());
    assertEquals("dll-not-found", f2.get(0).outcome());
    assertEquals("bar.dll", f2.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f3 =
        EngineOutputDiagnostic.parse("stderr", "baz.dll was not found", checkedAt);
    assertEquals(1, f3.size());
    assertEquals("dll-not-found", f3.get(0).outcome());
    assertEquals("baz.dll", f3.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f4 =
        EngineOutputDiagnostic.parse("stderr", "Missing DLL: qux.dll", checkedAt);
    assertEquals(1, f4.size());
    assertEquals("dll-not-found", f4.get(0).outcome());
    assertEquals("qux.dll", f4.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f5 =
        EngineOutputDiagnostic.parse("stderr", "Cannot find zlib1.dll", checkedAt);
    assertEquals(1, f5.size());
    assertEquals("dll-not-found", f5.get(0).outcome());
    assertEquals("zlib1.dll", f5.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f6 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "error while loading shared libraries: libopenblas.dll: cannot open shared object file",
            checkedAt);
    assertEquals(1, f6.size());
    assertEquals("dll-not-found", f6.get(0).outcome());
    assertEquals("libopenblas.dll", f6.get(0).dll());
  }

  @Test
  void parsesEntrypointImageInitAndGenericLoadOutcomes() {
    Instant checkedAt = Instant.now();

    // Entrypoint not found
    List<EngineStartupDiagnostics.Finding> fEntry =
        EngineOutputDiagnostic.parse(
            "stderr",
            "The procedure entry point cudaGetDevice could not be located in the dynamic link library nvcuda.dll",
            checkedAt);
    assertEquals(1, fEntry.size());
    assertEquals("entrypoint-not-found", fEntry.get(0).outcome());
    assertEquals("nvcuda.dll", fEntry.get(0).dll());

    // Invalid image format (bad image / error 193)
    List<EngineStartupDiagnostics.Finding> fImage1 =
        EngineOutputDiagnostic.parse(
            "stderr", "Could not load library bad.dll. Error code 193", checkedAt);
    assertEquals(1, fImage1.size());
    assertEquals("invalid-image-format", fImage1.get(0).outcome());
    assertEquals("bad.dll", fImage1.get(0).dll());

    List<EngineStartupDiagnostics.Finding> fImage2 =
        EngineOutputDiagnostic.parse(
            "stderr", "foo.dll is not a valid Win32 application", checkedAt);
    assertEquals(1, fImage2.size());
    assertEquals("invalid-image-format", fImage2.get(0).outcome());
    assertEquals("foo.dll", fImage2.get(0).dll());

    // DLL init failed (error 1114)
    List<EngineStartupDiagnostics.Finding> fInit1 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "A dynamic link library (DLL) initialization routine failed: init_fail.dll",
            checkedAt);
    assertEquals(1, fInit1.size());
    assertEquals("dll-init-failed", fInit1.get(0).outcome());
    assertEquals("init_fail.dll", fInit1.get(0).dll());

    List<EngineStartupDiagnostics.Finding> fInit2 =
        EngineOutputDiagnostic.parse(
            "stderr", "Could not load library init_fail.dll. Error code 1114", checkedAt);
    assertEquals(1, fInit2.size());
    assertEquals("dll-init-failed", fInit2.get(0).outcome());
    assertEquals("init_fail.dll", fInit2.get(0).dll());

    // Generic load failure (no error code)
    List<EngineStartupDiagnostics.Finding> fGeneric =
        EngineOutputDiagnostic.parse("stderr", "Failed to load library generic.dll", checkedAt);
    assertEquals(1, fGeneric.size());
    assertEquals("dll-load-failed", fGeneric.get(0).outcome());
    assertEquals("generic.dll", fGeneric.get(0).dll());
  }

  @Test
  void normalizesPathsQuotesAndPreservesCase() {
    Instant checkedAt = Instant.now();

    List<EngineStartupDiagnostics.Finding> f1 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "Could not load library 'C:\\Program Files\\NVIDIA\\cudnn64_9.dll'. Error code 126",
            checkedAt);
    assertEquals(1, f1.size());
    assertEquals("cudnn64_9.dll", f1.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f2 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "Could not load library \"C:/tools/engines/FOO.DLL\". Error code 126",
            checkedAt);
    assertEquals(1, f2.size());
    assertEquals("FOO.DLL", f2.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f3 =
        EngineOutputDiagnostic.parse(
            "stderr", "Missing DLL: [..\\libs\\libopenblas.dll]", checkedAt);
    assertEquals(1, f3.size());
    assertEquals("libopenblas.dll", f3.get(0).dll());
  }

  @Test
  void distinguishesStdoutAndStderrSources() {
    Instant checkedAt = Instant.now();
    String line = "foo.dll was not found";

    List<EngineStartupDiagnostics.Finding> stdoutFindings =
        EngineOutputDiagnostic.parse("stdout", line, checkedAt);
    assertEquals(1, stdoutFindings.size());
    assertEquals("engine-stdout", stdoutFindings.get(0).evidence());

    List<EngineStartupDiagnostics.Finding> stderrFindings =
        EngineOutputDiagnostic.parse("stderr", line, checkedAt);
    assertEquals(1, stderrFindings.size());
    assertEquals("engine-stderr", stderrFindings.get(0).evidence());
  }

  @Test
  void rejectsMereMentionsAndSuccessMessages() {
    Instant checkedAt = Instant.now();

    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "Using library: cudnn64_9.dll", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "Scanning foo.dll for symbols...", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "Successfully loaded cudnn64_9.dll", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "Loaded library foo.dll successfully", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "foo.dll loaded successfully", checkedAt).isEmpty());
  }

  @Test
  void rejectsNegatedFailures() {
    Instant checkedAt = Instant.now();

    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "cudnn64_9.dll is not missing", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "Did not fail to load foo.dll", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "No missing DLLs found (checked foo.dll)", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse("stderr", "foo.dll was found, not missing", checkedAt)
            .isEmpty());
  }

  @Test
  void rejectsUnrelatedErrorsOnLinesMentioningDlls() {
    Instant checkedAt = Instant.now();

    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr",
                "Error: board size 19 is invalid, config path is C:\\tools\\libfoo.dll\\config.txt",
                checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr", "Syntax error: unexpected token near foo.dll in input command", checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr",
                "Failed to parse GTP command 'loadsgf foo.dll': file format error",
                checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr",
                "Failed to allocate memory (1024MB) while processing query with model foo.dll",
                checkedAt)
            .isEmpty());
    assertTrue(
        EngineOutputDiagnostic.parse(
                "stderr",
                "Error: network timeout connecting to 127.0.0.1:8080 (model foo.dll)",
                checkedAt)
            .isEmpty());
  }

  @Test
  void extractsFailureWhenLineHasSeparateSuccessOrUnrelatedClauses() {
    Instant checkedAt = Instant.now();
    String line =
        "Successfully loaded kernel32.dll; could not load library cudnn64_9.dll. Error code 126";

    List<EngineStartupDiagnostics.Finding> findings =
        EngineOutputDiagnostic.parse("stderr", line, checkedAt);

    assertEquals(1, findings.size());
    assertEquals("cudnn64_9.dll", findings.get(0).dll());
    assertEquals("dll-not-found", findings.get(0).outcome());
  }

  @Test
  void boundsDetailWithoutStallingOnRetainedOutput() {
    Instant checkedAt = Instant.now();

    String massiveLine = "Could not load library cudnn64_9.dll. Error code 126" + "x".repeat(16000);
    List<EngineStartupDiagnostics.Finding> f1 =
        org.junit.jupiter.api.Assertions.assertTimeout(
            java.time.Duration.ofSeconds(2),
            () -> EngineOutputDiagnostic.parse("stderr", massiveLine, checkedAt));
    assertEquals(1, f1.size());
    assertEquals("cudnn64_9.dll", f1.get(0).dll());
    assertTrue(
        f1.get(0).detail().getBytes(StandardCharsets.UTF_8).length
            <= EngineOutputDiagnostic.MAX_DETAIL_BYTES);

    // Null or blank returns empty
    assertTrue(EngineOutputDiagnostic.parse("stderr", null, checkedAt).isEmpty());
    assertTrue(EngineOutputDiagnostic.parse("stderr", "   \n\t  ", checkedAt).isEmpty());
  }

  @Test
  void attemptRetainsExplicitErrorBeyondChar4096WithinTailLimit() {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt = service.begin("eng-long-line", "MAIN_BOARD", List.of("engine.exe"), true);
      String longLine = "x".repeat(4500) + "; Could not load library cudnn64_9.dll. Error code 126";
      attempt.output("stderr", longLine);
      var failure = attempt.fail("startup-exit", "process failed");
      var json = failure.toJson();

      boolean attributed = false;
      var findings = json.getJSONArray("findings");
      for (int i = 0; i < findings.length(); i++) {
        var finding = findings.getJSONObject(i);
        if ("cudnn64_9.dll".equals(finding.optString("dll"))
            && "dll-not-found".equals(finding.optString("outcome"))) {
          attributed = true;
          break;
        }
      }

      boolean explicitlyPartial =
          "partial".equals(json.optString("outcome")) || json.optBoolean("truncated", false);

      assertTrue(
          attributed || explicitlyPartial,
          "Explicit error after char 4096 under 16KiB must be attributed or explicitly partial, but was silently omitted: "
              + json);
    }
  }

  @Test
  void attemptRetainsSeventeenDistinctShortErrorsOrMarksPartial() {
    try (EngineStartupDiagnostics service =
        new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      var attempt = service.begin("eng-17-errors", "MAIN_BOARD", List.of("engine.exe"), true);
      for (int i = 0; i < 17; i++) {
        attempt.output("stderr", "foo_" + i + ".dll was not found");
      }
      var failure = attempt.fail("startup-exit", "process failed");
      var json = failure.toJson();

      var findings = json.getJSONArray("findings");
      boolean allAttributed = findings.length() == 17;
      boolean explicitlyPartial =
          "partial".equals(json.optString("outcome")) || json.optBoolean("truncated", false);

      assertTrue(
          allAttributed || explicitlyPartial,
          "17 distinct short errors must all be attributed or explicitly partial, but only "
              + findings.length()
              + " were reported without partial/truncated marking: "
              + json);
    }
  }

  @Test
  void rejectsConfigFormatErrorWithDllFilename() {
    Instant checkedAt = Instant.now();
    var findings =
        EngineOutputDiagnostic.parse(
            "stderr", "Config format error while reading C:\\models\\helper.dll", checkedAt);
    assertTrue(
        findings.isEmpty(),
        "Non-loader config format error mentioning DLL must not produce findings, but got: "
            + findings);
  }

  @Test
  void preservesTrueBadImageAndErrorCode193Outcomes() {
    Instant checkedAt = Instant.now();

    List<EngineStartupDiagnostics.Finding> f1 =
        EngineOutputDiagnostic.parse(
            "stderr", "Could not load library bad.dll. Error code 193", checkedAt);
    assertEquals(1, f1.size());
    assertEquals("invalid-image-format", f1.get(0).outcome());
    assertEquals("bad.dll", f1.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f2 =
        EngineOutputDiagnostic.parse(
            "stderr",
            "Bad Image: C:\\tools\\corrupt.dll is either not designed to run on Windows or it contains an error.",
            checkedAt);
    assertEquals(1, f2.size());
    assertEquals("invalid-image-format", f2.get(0).outcome());
    assertEquals("corrupt.dll", f2.get(0).dll());

    List<EngineStartupDiagnostics.Finding> f3 =
        EngineOutputDiagnostic.parse("stderr", "invalid image format: bad_arch.dll", checkedAt);
    assertEquals(1, f3.size());
    assertEquals("invalid-image-format", f3.get(0).outcome());
    assertEquals("bad_arch.dll", f3.get(0).dll());
  }
}
