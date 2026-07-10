package featurecat.lizzie.gui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Coalesces bursty background updates into bounded-rate work on the Swing event thread. */
final class SwingRefreshCoalescer {
  private final long minimumIntervalNanos;
  private final Runnable task;
  private final AtomicBoolean pending = new AtomicBoolean(false);
  private final AtomicLong requestedGeneration = new AtomicLong();
  private long lastRunNanos = Long.MIN_VALUE;
  private Timer timer;

  SwingRefreshCoalescer(int minimumIntervalMillis, Runnable task) {
    if (minimumIntervalMillis < 0) {
      throw new IllegalArgumentException("minimumIntervalMillis must not be negative");
    }
    this.minimumIntervalNanos = minimumIntervalMillis * 1_000_000L;
    this.task = java.util.Objects.requireNonNull(task, "task");
  }

  void request() {
    requestedGeneration.incrementAndGet();
    enqueueIfNeeded();
  }

  private void enqueueIfNeeded() {
    if (!pending.compareAndSet(false, true)) {
      return;
    }
    if (SwingUtilities.isEventDispatchThread()) {
      scheduleOnEventThread();
    } else {
      SwingUtilities.invokeLater(this::scheduleOnEventThread);
    }
  }

  private void scheduleOnEventThread() {
    long now = System.nanoTime();
    long elapsed = lastRunNanos == Long.MIN_VALUE ? Long.MAX_VALUE : now - lastRunNanos;
    long remaining = minimumIntervalNanos - elapsed;
    if (remaining <= 0L) {
      runTask();
      return;
    }

    int delayMillis = (int) Math.max(1L, (remaining + 999_999L) / 1_000_000L);
    if (timer == null) {
      timer = new Timer(delayMillis, event -> runTask());
      timer.setRepeats(false);
    } else {
      timer.setInitialDelay(delayMillis);
    }
    timer.restart();
  }

  private void runTask() {
    long generation = requestedGeneration.get();
    lastRunNanos = System.nanoTime();
    try {
      task.run();
    } finally {
      pending.set(false);
      if (requestedGeneration.get() != generation) {
        enqueueIfNeeded();
      }
    }
  }
}
