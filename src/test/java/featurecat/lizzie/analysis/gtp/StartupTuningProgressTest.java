package featurecat.lizzie.analysis.gtp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StartupTuningProgressTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "Beginning GPU tuning for GTX 750 Ti modelVersion 17 channels 768",
        "Performing autotuning",
        ": Performing autotuning",
        "2026-11-19 20:30:00-0800: Performing autotuning",
        "Performing autotuning for ALL neural net configurations needed for the run!",
        "Tuning xGemmDirect for 1x1 convolutions and matrix mult",
        "Tuning transformer attention kernel",
        "Tuning transformerRMSNorm",
        "Tuning pointWise (addPointWise + swiGLU)",
        "Tuning spatialRMSNorm",
        "Tuning addChannelBiasesNCHW",
        "Tuning 10/100 ...",
        "Tuning 0/100 (reference) Calls/sec 1234.5 ErrorProp 0.001 MWG=16",
        "Tuning * 12/100 Calls/sec 2345.6 ErrorProp 0.002",
        "Tuning   13/100 Calls/sec 1234.5 ErrorProp 0.003",
        "  Tuning 25/100 2.4 GFLOPS"
      })
  void recognizesActualTunerOutput(String line) {
    assertTrue(new StartupTuningProgress().observe(line, 0));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "info move D4 visits 20",
        "Loaded tuning parameters from: tuner.txt",
        "Tuning 10/100 failed: error",
        "Tuning transformer attention failed",
        "This error took a long time",
        "=1 KataGo",
        ""
      })
  void ordinaryOutputCannotKeepAStuckEngineAlive(String line) {
    StartupTuningProgress progress = new StartupTuningProgress();
    assertFalse(progress.observe(line, 100));
    assertEquals(20, progress.extendDeadline(20, 100, 10));
  }

  @Test
  void progressContinuesPastTheOldTenMinuteTotalLimitButSilenceExpires() {
    StartupTuningProgress progress = new StartupTuningProgress();
    long deadline = seconds(90);
    for (int minute = 0; minute <= 25; minute++) {
      long now = seconds(minute * 60L);
      assertTrue(progress.observe("Tuning " + minute + "/100 ...", now));
      deadline = progress.extendDeadline(deadline, now, 600_000);
      assertEquals(now + seconds(600), deadline);
    }
    long expired = seconds(35 * 60);
    assertEquals(expired, progress.extendDeadline(deadline, expired, 600_000));
    assertEquals(expired, progress.extendDeadline(deadline, expired + 1, 600_000));
  }

  @Test
  void continuingOutputStillHasAHardLimitAndAReplacementStartsFresh() {
    StartupTuningProgress progress = new StartupTuningProgress();
    progress.observe("Tuning 1/100 ...", 0);
    long almostHour = seconds(59 * 60);
    progress.observe("Tuning 99/100 ...", almostHour);
    assertEquals(seconds(3600), progress.extendDeadline(0, almostHour, 600_000));
    assertEquals(123, new StartupTuningProgress().extendDeadline(123, almostHour, 600_000));
  }

  @Test
  void reorderedReadersCannotMoveProgressBackwards() {
    StartupTuningProgress progress = new StartupTuningProgress();
    progress.observe("Tuning 1/100 ...", 0);
    progress.observe("Tuning 3/100 ...", seconds(30));
    progress.observe("Tuning 2/100 ...", seconds(20));
    assertEquals(seconds(40), progress.extendDeadline(0, seconds(30), 10_000));
  }

  private static long seconds(long seconds) {
    return TimeUnit.SECONDS.toNanos(seconds);
  }
}
