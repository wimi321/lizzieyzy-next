package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Temporarily releases foreground analysis resources while another engine is starting. */
final class ForegroundAnalysisPause {
  private final BooleanSupplier currentTarget;
  private final BooleanSupplier pondering;
  private final Runnable resume;
  private boolean restorePending;

  private ForegroundAnalysisPause(
      BooleanSupplier currentTarget,
      BooleanSupplier pondering,
      Runnable resume,
      boolean restorePending) {
    this.currentTarget = currentTarget;
    this.pondering = pondering;
    this.resume = resume;
    this.restorePending = restorePending;
  }

  static ForegroundAnalysisPause inactive() {
    return new ForegroundAnalysisPause(() -> false, () -> false, () -> {}, false);
  }

  static ForegroundAnalysisPause pauseCurrent() {
    Leelaz engine = Lizzie.leelaz;
    if (engine == null) {
      return inactive();
    }
    return acquire(
        () -> Lizzie.leelaz == engine && engine.isStarted(),
        engine::isPondering,
        () -> {
          engine.notPondering();
          engine.nameCmd();
        },
        engine::ponder);
  }

  static ForegroundAnalysisPause acquire(
      BooleanSupplier currentTarget,
      BooleanSupplier pondering,
      Runnable pause,
      Runnable resume) {
    Objects.requireNonNull(currentTarget, "currentTarget");
    Objects.requireNonNull(pondering, "pondering");
    Objects.requireNonNull(pause, "pause");
    Objects.requireNonNull(resume, "resume");
    if (!pondering.getAsBoolean()) {
      return inactive();
    }
    pause.run();
    return new ForegroundAnalysisPause(currentTarget, pondering, resume, true);
  }

  synchronized boolean transferRestoreResponsibility() {
    boolean pending = restorePending;
    restorePending = false;
    return pending;
  }

  synchronized void restore() {
    if (!restorePending) {
      return;
    }
    restorePending = false;
    if (currentTarget.getAsBoolean() && !pondering.getAsBoolean()) {
      resume.run();
    }
  }

  synchronized boolean isRestorePending() {
    return restorePending;
  }
}
