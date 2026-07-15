package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class KataGoRuntimeHelperBenchmarkLeaseTest {

  @Test
  void activeForegroundLeaseRejectsBenchmarkWithoutChangingPauseState() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-lease"));
    Leelaz engine = reusableKatagoEngine();
    try {
      Lizzie.config = config;
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      config.showPonderLimitedTips = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();

      assertFalse(result.accepted());
      assertFalse(result.analysisWasPondering());
      assertTrue(config.showPonderLimitedTips);
      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertTrue(engine.isLoaded());
      assertTrue(engine.isStarted());
    } finally {
      engine.endExclusiveGtpSession();
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void acceptedBenchmarkPauseBlocksNewForegroundLeaseUntilRestore() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-reservation"));
    Leelaz engine = reusableKatagoEngine();
    boolean pauseAccepted = false;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = null;
      Lizzie.frame = null;

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      pauseAccepted = result.accepted();
      Lizzie.leelaz = engine;

      assertTrue(result.accepted());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    } finally {
      if (pauseAccepted) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void secondBenchmarkPauseIsRejectedWithoutClearingFirstPauseState() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-reentry"));
    boolean pauseAccepted = false;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = null;
      Lizzie.frame = null;

      KataGoRuntimeHelper.BenchmarkPauseResult first =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      pauseAccepted = first.accepted();
      KataGoRuntimeHelper.BenchmarkPauseResult second =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();

      assertTrue(first.accepted());
      assertFalse(second.accepted());
      assertTrue(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
    } finally {
      if (pauseAccepted) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
    assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
  }

  private static Leelaz reusableKatagoEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-set-rules",
            "clear_board",
            "play",
            "kata-analyze"));
    Field capabilityField = Leelaz.class.getDeclaredField("endGetCommandList");
    capabilityField.setAccessible(true);
    capabilityField.set(engine, true);
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, new BufferedOutputStream(new ByteArrayOutputStream()));
    return engine;
  }
}
