package featurecat.lizzie.gui;

import java.util.concurrent.CompletableFuture;
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
