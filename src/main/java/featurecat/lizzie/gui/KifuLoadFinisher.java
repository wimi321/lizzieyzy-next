package featurecat.lizzie.gui;

import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

final class KifuLoadFinisher {
  private KifuLoadFinisher() {}

  static void finishAfterRefresh(Runnable refresh, Runnable complete) {
    finishAfterRefresh(refresh, complete, 120);
  }

  static void finishAfterRefresh(Runnable refresh, Runnable complete, int delayMillis) {
    finishAfterRefresh(refresh, complete, delayMillis, Throwable::printStackTrace);
  }

  static void finishAfterRefresh(
      Runnable refresh, Runnable complete, int delayMillis, Consumer<Throwable> failureReporter) {
    Runnable finish =
        new Runnable() {
          public void run() {
            Timer finishTimer = createFinishTimer(complete, delayMillis);
            finishTimer.start();
            try {
              if (refresh != null) {
                refresh.run();
              }
            } catch (Throwable t) {
              if (failureReporter != null) {
                failureReporter.accept(t);
              }
            }
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      finish.run();
    } else {
      SwingUtilities.invokeLater(finish);
    }
  }

  private static Timer createFinishTimer(Runnable complete, int delayMillis) {
    Timer timer =
        new Timer(
            Math.max(0, delayMillis),
            e -> {
              if (complete != null) {
                complete.run();
              }
            });
    timer.setRepeats(false);
    return timer;
  }
}
