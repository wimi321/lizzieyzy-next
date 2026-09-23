package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.PerformanceProbeEngineAccess;
import featurecat.lizzie.rules.Board;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Test-only preparation ordering; a GTP ACK alone cannot drain deferred board restoration. */
final class PerformanceProbePreparation {
  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  @FunctionalInterface
  interface Command {
    void run(String command) throws Exception;
  }

  private PerformanceProbePreparation() {}

  static CompletableFuture<Void> ordinaryHistoryRestored(Leelaz engine)
      throws ReflectiveOperationException {
    // Observe completion of the real queue; do not insert an additional position restore, which
    // would supersede an earlier ordinary navigation/resume restore captured on the same EDT turn.
    java.lang.reflect.Field executor = Board.class.getDeclaredField("HISTORY_RESTORE_EXECUTOR");
    executor.setAccessible(true);
    return afterOrdinaryHistory(
        (Executor) executor.get(null), () -> PerformanceProbeEngineAccess.confirmPosition(engine));
  }

  static CompletableFuture<Void> afterOrdinaryHistory(
      Executor historyExecutor,
      java.util.function.Supplier<CompletableFuture<Void>> confirmPosition) {
    return CompletableFuture.runAsync(() -> {}, historyExecutor)
        .thenCompose(ignored -> confirmPosition.get());
  }

  static void realtime(
      CompletableFuture<Void> restored, Action armAnalysis, Command acknowledgedCommand)
      throws Exception {
    if (SwingUtilities.isEventDispatchThread())
      throw new IllegalStateException("Do not wait for board restoration on the EDT");
    restored.get(30, TimeUnit.SECONDS);
    armAnalysis.run();
    // Keep the ordinary analysis intent active for the measured move, but stop the current search
    // before clearing its cache. Neither command may overtake work still queued by Board.
    acknowledgedCommand.run("stop");
    acknowledgedCommand.run("clear_cache");
  }
}
