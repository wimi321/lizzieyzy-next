package featurecat.lizzie.logging;

import java.util.concurrent.TimeUnit;

final class Deadline {
  private Deadline() {}

  static void run(long deadlineNanos, Runnable action) {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      return;
    }
    Thread thread = new Thread(action, "lizzie-log-deadline");
    thread.setDaemon(true);
    thread.start();
    try {
      thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      thread.interrupt();
    }
  }
}
