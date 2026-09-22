package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasuredKataGoTuningTest {
  @TempDir Path directory;
  private static final MeasuredKataGoTuning.Verification VERIFIED = (entry, command, report) -> {};

  @Test
  void reviewDoesNotApplyAndSceneProfilesPreserveCommandsLegacyPoliciesAndOtherEntries()
      throws Exception {
    try (Environment env = new Environment()) {
      var live = MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.LIVE), VERIFIED);
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
      MeasuredKataGoTuning.apply(live, VERIFIED);
      var whole = MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.WHOLE_GAME), VERIFIED);
      MeasuredKataGoTuning.apply(whole, VERIFIED);
      EngineData current = EngineThreadPolicy.findSavedEntry(env.entry.id);
      assertEquals(env.entry.commands, current.commands);
      assertEquals(env.analysis, Lizzie.config.analysisEngineCommand);
      assertEquals("keep-apple", current.threadPolicy.getString("katago-apple-tuning-profile-v1"));
      assertEquals(2, current.threadPolicy.getJSONObject(MeasuredKataGoTuning.KEY).length());
      assertFalse(MeasuredKataGoTuning.hasProfile(env.other.id));
      MeasuredKataGoTuning.restore(env.entry.id);
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
      assertEquals(env.entry.commands, EngineThreadPolicy.findSavedEntry(env.entry.id).commands);
      assertEquals(
          "keep-apple",
          EngineThreadPolicy.findSavedEntry(env.entry.id)
              .threadPolicy
              .getString("katago-apple-tuning-profile-v1"));
    }
  }

  @Test
  void editsDuringReviewOrFinalVerificationCannotBeOverwritten() throws Exception {
    try (Environment env = new Environment()) {
      var review = MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.LIVE), VERIFIED);
      assertThrows(
          IOException.class,
          () ->
              MeasuredKataGoTuning.apply(
                  review,
                  (entry, command, report) -> {
                    var entries = Utils.getEngineData();
                    entries.get(0).commands += " -override-config numSearchThreads=32";
                    Utils.saveEngineSettings(entries);
                  }));
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
      assertTrue(
          EngineThreadPolicy.findSavedEntry(env.entry.id).commands.endsWith("numSearchThreads=32"));
      assertThrows(IOException.class, () -> MeasuredKataGoTuning.apply(review, VERIFIED));
    }
  }

  @Test
  void failedPersistenceKeepsPublishedSettingsAndOriginalFile() throws Exception {
    try (Environment env = new Environment()) {
      var review = MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.LIVE), VERIFIED);
      Path config = directory.resolve("config.txt");
      String original = Files.readString(config);
      Path backup = directory.resolve("config-original");
      Files.move(config, backup);
      Files.createDirectory(config);
      Files.writeString(config.resolve("occupied"), "fixture");
      assertThrows(IOException.class, () -> MeasuredKataGoTuning.apply(review, VERIFIED));
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
      assertEquals(original, Files.readString(backup));
      assertEquals(env.entry.commands, EngineThreadPolicy.findSavedEntry(env.entry.id).commands);
    }
  }

  @Test
  void sceneOverlayChangesOnlyWhitelistedConcurrencyAndRestoreRetainsLaterManualEdits()
      throws Exception {
    try (Environment env = new Environment()) {
      MeasuredKataGoTuning.apply(
          MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.WHOLE_GAME), VERIFIED),
          VERIFIED);
      EngineData entry = EngineThreadPolicy.findSavedEntry(env.entry.id);
      List<String> original =
          List.of(
              "katago",
              "analysis",
              "-override-config",
              "maxVisits=5000,rules=chinese,nnUseFP16=true");
      List<String> tuned =
          MeasuredKataGoTuning.applyOverlay(
              original, entry, env.analysis, Scene.WHOLE_GAME, VERIFIED);
      var values = KataGoCommandSpec.parse(tuned).effectiveOverrides();
      assertEquals("5000", values.get("maxVisits"));
      assertEquals("chinese", values.get("rules"));
      assertEquals("true", values.get("nnUseFP16"));
      assertEquals("12", values.get("numAnalysisThreads"));
      assertEquals("2", values.get("numSearchThreadsPerAnalysisThread"));
      assertEquals("", values.get("numSearchThreads"));
      assertSame(
          original,
          MeasuredKataGoTuning.applyOverlay(
              original, entry, env.entry.commands, Scene.LIVE, VERIFIED));
      assertSame(
          original,
          MeasuredKataGoTuning.applyOverlay(
              original, entry, env.analysis + " changed", Scene.WHOLE_GAME, VERIFIED));
      var entries = Utils.getEngineData();
      entries.get(0).commands += " -override-config numSearchThreads=32";
      Utils.saveEngineSettings(entries);
      MeasuredKataGoTuning.restore(env.entry.id);
      assertTrue(
          EngineThreadPolicy.findSavedEntry(env.entry.id).commands.endsWith("numSearchThreads=32"));
    }
  }

  @Test
  void invalidEvidenceAndFingerprintMismatchCannotBeApplied() throws Exception {
    try (Environment env = new Environment()) {
      Path file = env.report(Scene.LIVE);
      JSONObject invalid = new JSONObject(Files.readString(file));
      invalid.getJSONArray("runs").getJSONObject(1).put("seconds", 20);
      Files.writeString(file, invalid.toString());
      AtomicInteger verification = new AtomicInteger();
      Path invalidFile = file;
      assertThrows(
          IOException.class,
          () ->
              MeasuredKataGoTuning.review(
                  env.entry.id,
                  invalidFile,
                  (entry, command, report) -> verification.incrementAndGet()));
      assertEquals(0, verification.get());
      file = env.report(Scene.LIVE);
      Path validFile = file;
      assertThrows(
          IOException.class,
          () ->
              MeasuredKataGoTuning.review(
                  env.entry.id,
                  validFile,
                  (entry, command, report) -> {
                    throw new IOException("changed model");
                  }));
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
    }
  }

  @Test
  void staleRuntimeFingerprintAndEventThreadLeaveCommandUnmodified() throws Exception {
    try (Environment env = new Environment()) {
      MeasuredKataGoTuning.apply(
          MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.LIVE), VERIFIED), VERIFIED);
      EngineData entry = EngineThreadPolicy.findSavedEntry(env.entry.id);
      List<String> command = List.of("katago", "gtp");
      assertSame(
          command,
          MeasuredKataGoTuning.applyOverlay(
              command,
              entry,
              entry.commands,
              Scene.LIVE,
              (target, configured, report) -> {
                throw new IOException("driver changed");
              }));
      SwingUtilities.invokeAndWait(
          () -> {
            assertSame(command, MeasuredKataGoTuning.applyLive(command, entry));
            assertSame(command, MeasuredKataGoTuning.applyWholeGame(command, env.analysis));
            assertThrows(IOException.class, () -> MeasuredKataGoTuning.restore(entry.id));
          });
    }
  }

  @Test
  void independentRemoteAnalysisIsRejectedEvenWhenTheSavedPrimaryEntryIsLocal() throws Exception {
    assertUnsupportedWholeGameMode(false);
  }

  @Test
  void reusingTheCurrentEngineCannotApplyAnIndependentWholeGameReport() throws Exception {
    assertUnsupportedWholeGameMode(true);
  }

  private void assertUnsupportedWholeGameMode(boolean reuse) throws Exception {
    try (Environment env = new Environment()) {
      Path report = env.report(Scene.WHOLE_GAME);
      var review = MeasuredKataGoTuning.review(env.entry.id, report, VERIFIED);
      setUnsupportedMode(reuse, true);
      AtomicInteger verifications = new AtomicInteger();
      MeasuredKataGoTuning.Verification verification =
          (entry, command, evidence) -> verifications.incrementAndGet();
      IOException rejected = assertThrows(
          IOException.class, () -> MeasuredKataGoTuning.review(env.entry.id, report, verification));
      assertTrue(rejected.getMessage().contains(reuse ? "reusing" : "SSH"));
      assertThrows(IOException.class, () -> MeasuredKataGoTuning.apply(review, verification));
      assertEquals(0, verifications.get());
      assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
      assertFalse(EngineThreadPolicy.findSavedEntry(env.entry.id).useJavaSSH);
      assertEquals(env.analysis, Lizzie.config.analysisEngineCommand);
      // A separate analysis mode must not disable a genuinely local live-scene report.
      assertNotNull(MeasuredKataGoTuning.review(env.entry.id, env.report(Scene.LIVE), VERIFIED));
      setUnsupportedMode(reuse, false);
      MeasuredKataGoTuning.apply(review, VERIFIED);
      EngineData entry = EngineThreadPolicy.findSavedEntry(env.entry.id);
      List<String> original = List.of("katago", "analysis");
      setUnsupportedMode(reuse, true);
      assertSame(original, MeasuredKataGoTuning.applyOverlay(
          original, entry, env.analysis, Scene.WHOLE_GAME, verification));
      assertSame(original, MeasuredKataGoTuning.applyWholeGame(original, env.analysis));
      assertEquals(0, verifications.get());
    }
  }

  @Test
  void sceneModeChangesDuringFingerprintVerificationFailClosedBeforeReviewCommitOrLaunch()
      throws Exception {
    for (boolean reuse : new boolean[] {false, true}) {
      try (Environment env = new Environment()) {
        Path report = env.report(Scene.WHOLE_GAME);
        MeasuredKataGoTuning.Verification changeDuringVerification =
            (entry, command, evidence) -> setUnsupportedMode(reuse, true);
        assertThrows(IOException.class,
            () -> MeasuredKataGoTuning.review(env.entry.id, report, changeDuringVerification));
        setUnsupportedMode(reuse, false);
        var review = MeasuredKataGoTuning.review(env.entry.id, report, VERIFIED);
        assertThrows(IOException.class,
            () -> MeasuredKataGoTuning.apply(review, changeDuringVerification));
        assertFalse(MeasuredKataGoTuning.hasProfile(env.entry.id));
        setUnsupportedMode(reuse, false);
        MeasuredKataGoTuning.apply(review, VERIFIED);
        EngineData entry = EngineThreadPolicy.findSavedEntry(env.entry.id);
        List<String> original = List.of("katago", "analysis");
        assertSame(original, MeasuredKataGoTuning.applyOverlay(
            original, entry, env.analysis, Scene.WHOLE_GAME, changeDuringVerification));
      }
    }
  }

  private static void setUnsupportedMode(boolean reuse, boolean enabled) {
    if (reuse) Lizzie.config.analysisReuseCurrentEngine = enabled;
    else Lizzie.config.leelazConfig.put(
        "analysis-engine-ssh-info", new JSONObject().put("useJavaSSH", enabled));
  }

  private final class Environment implements AutoCloseable {
    final Config previous = Lizzie.config;
    final EngineData entry = new EngineData();
    final EngineData other = new EngineData();
    final String analysis = "katago analysis -config analysis.cfg -model model.bin.gz";

    Environment() {
      Lizzie.config = ConfigTestHelper.createForTests(directory);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.analysisEngineCommand = analysis;
      Lizzie.config.analysisReuseCurrentEngine = false;
      entry.commands = "katago gtp -config gtp.cfg -model model.bin.gz";
      entry.name = "measured";
      entry.threadPolicy = new JSONObject().put("katago-apple-tuning-profile-v1", "keep-apple");
      other.commands = entry.commands;
      other.name = "other";
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry, other)));
    }

    Path report(Scene scene) throws IOException {
      JSONObject fingerprint =
          new JSONObject()
              .put("engineSha256", "a".repeat(64))
              .put("modelSha256", "b".repeat(64))
              .put("configSha256", "c".repeat(64))
              .put("configIncludes", new JSONArray())
              .put("gpuName", "test GPU")
              .put("driverVersion", "test driver")
              .put("memoryMiB", 10000)
              .put("commandSemantics", new JSONObject());
      JSONObject baseline = new JSONObject().put("nnMaxBatchSize", 16);
      JSONObject candidate = new JSONObject().put("nnMaxBatchSize", 32);
      if (scene == Scene.LIVE) {
        baseline.put("numSearchThreads", 8);
        candidate.put("numSearchThreads", 16);
      } else {
        baseline.put("numAnalysisThreads", 8).put("numSearchThreadsPerAnalysisThread", 2);
        candidate.put("numAnalysisThreads", 12).put("numSearchThreadsPerAnalysisThread", 2);
      }
      JSONArray runs = new JSONArray();
      for (int round = 1; round <= 3; round++) {
        for (int member = 0; member < 2; member++) {
          boolean base = (round + member) % 2 == 1;
          JSONObject run =
              new JSONObject()
                  .put("profile", base ? "baseline" : "candidate")
                  .put("round", round)
                  .put("phase", "warm")
                  .put("seconds", base ? 10 : 8)
                  .put("responseSeconds", .05)
                  .put("edtP95Seconds", .01)
                  .put("maxMemoryMiB", 2000)
                  .put("maxEngineProcesses", 1);
          if (scene == Scene.LIVE)
            run.put("observedRootVisits", 5000).put("firstResultSeconds", .1);
          else run.put("rootVisitsByTurn", new JSONArray(List.of(5000, 5000)));
          runs.put(run);
        }
      }
      JSONObject report =
          new JSONObject()
              .put("schemaVersion", 1)
              .put("scene", scene.id())
              .put("measurementMode", "application")
              .put("fingerprint", fingerprint)
              .put("fixtureSha256", "d".repeat(64))
              .put("budget", 5000)
              .put("positions", scene == Scene.LIVE ? 1 : 2)
              .put("metricScope", "totalGpu")
              .put("baselineParameters", baseline)
              .put("candidateParameters", candidate)
              .put("runs", runs);
      Path path = directory.resolve(scene.id() + ".json");
      Files.writeString(path, report.toString());
      return path;
    }

    @Override
    public void close() {
      Lizzie.config = previous;
    }
  }
}
