package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ForegroundAnalysisPauseTest {
  @Test
  void inactiveAnalysisIsNotPausedOrResumed() {
    AtomicInteger pauses = new AtomicInteger();
    AtomicInteger resumes = new AtomicInteger();

    ForegroundAnalysisPause lease =
        ForegroundAnalysisPause.acquire(
            () -> true, () -> false, pauses::incrementAndGet, resumes::incrementAndGet);

    assertFalse(lease.isRestorePending());
    lease.restore();
    assertFalse(lease.transferRestoreResponsibility());
    assertEquals(0, pauses.get());
    assertEquals(0, resumes.get());
  }

  @Test
  void failedPreparationRestoresTheSameForegroundAnalysis() {
    AtomicBoolean pondering = new AtomicBoolean(true);
    AtomicInteger pauses = new AtomicInteger();
    AtomicInteger resumes = new AtomicInteger();

    ForegroundAnalysisPause lease =
        ForegroundAnalysisPause.acquire(
            () -> true,
            pondering::get,
            () -> {
              pauses.incrementAndGet();
              pondering.set(false);
            },
            () -> {
              resumes.incrementAndGet();
              pondering.set(true);
            });

    assertTrue(lease.isRestorePending());
    assertEquals(1, pauses.get());
    assertFalse(pondering.get());
    lease.restore();
    lease.restore();
    assertTrue(pondering.get());
    assertEquals(1, resumes.get());
  }

  @Test
  void successfulPreparationTransfersRestoreResponsibilityToTheGame() {
    AtomicBoolean pondering = new AtomicBoolean(true);
    AtomicInteger resumes = new AtomicInteger();

    ForegroundAnalysisPause lease =
        ForegroundAnalysisPause.acquire(
            () -> true,
            pondering::get,
            () -> pondering.set(false),
            () -> {
              resumes.incrementAndGet();
              pondering.set(true);
            });

    assertTrue(lease.transferRestoreResponsibility());
    assertFalse(lease.transferRestoreResponsibility());
    lease.restore();
    assertFalse(pondering.get());
    assertEquals(0, resumes.get());
  }

  @Test
  void staleEngineIsNeverRestarted() {
    AtomicBoolean pondering = new AtomicBoolean(true);
    AtomicInteger resumes = new AtomicInteger();

    ForegroundAnalysisPause lease =
        ForegroundAnalysisPause.acquire(
            () -> false,
            pondering::get,
            () -> pondering.set(false),
            resumes::incrementAndGet);

    lease.restore();
    assertFalse(pondering.get());
    assertEquals(0, resumes.get());
  }
}
