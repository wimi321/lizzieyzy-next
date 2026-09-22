package featurecat.lizzie.gui.web;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A single pending notification, with board changes taking priority over analysis refreshes. */
final class WebBoardUpdateQueue {
  private final ScheduledThreadPoolExecutor executor;
  private final Consumer<Update> broadcast;
  private final long minimumIntervalNanos;
  private ScheduledFuture<?> scheduled;
  private boolean running;
  private boolean analysisPending;
  private boolean fullStatePending;
  private boolean closed;
  private long boardGeneration;
  private long scheduleGeneration;
  private long lastBroadcastNanos;
  private boolean hasBroadcast;

  WebBoardUpdateQueue(
      ScheduledThreadPoolExecutor executor,
      long minimumIntervalMillis,
      Consumer<Update> broadcast) {
    this.executor = executor;
    this.minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMillis);
    this.broadcast = broadcast;
  }

  synchronized void requestAnalysis() {
    if (closed) return;
    analysisPending = true;
    scheduleIfNeeded();
  }

  synchronized void requestFullState() {
    if (closed) return;
    boardGeneration++;
    if (!fullStatePending) {
      fullStatePending = true;
      // Promote a throttled analysis notification without retaining its cancelled timer in the
      // queue.
      if (scheduled != null) {
        scheduled.cancel(false);
        executor.remove((Runnable) scheduled);
        scheduled = null;
        scheduleGeneration++;
      }
    }
    scheduleIfNeeded();
  }

  private void scheduleIfNeeded() {
    if (closed || running || scheduled != null || (!analysisPending && !fullStatePending)) return;
    long delay =
        fullStatePending || !hasBroadcast
            ? 0
            : Math.max(0, minimumIntervalNanos - (System.nanoTime() - lastBroadcastNanos));
    long generation = ++scheduleGeneration;
    try {
      scheduled = executor.schedule(() -> drain(generation), delay, TimeUnit.NANOSECONDS);
    } catch (RejectedExecutionException ignored) {
      close();
    }
  }

  private void drain(long generation) {
    Update update;
    synchronized (this) {
      // A cancelled timer may already be waiting for this lock when a full state promotes it.
      if (closed || generation != scheduleGeneration) return;
      scheduled = null;
      running = true;
      update = new Update(fullStatePending, boardGeneration);
      fullStatePending = false;
      analysisPending = false;
    }
    try {
      broadcast.accept(update);
    } finally {
      synchronized (this) {
        // Include serialization and slow broadcasts in the interval; never catch up with a burst.
        lastBroadcastNanos = System.nanoTime();
        hasBroadcast = true;
        running = false;
        scheduleIfNeeded();
      }
    }
  }

  synchronized void close() {
    closed = true;
    analysisPending = false;
    fullStatePending = false;
    scheduleGeneration++;
    if (scheduled != null) {
      scheduled.cancel(false);
      executor.remove((Runnable) scheduled);
      scheduled = null;
    }
  }

  final class Update {
    final boolean fullState;
    private final long generation;

    private Update(boolean fullState, long generation) {
      this.fullState = fullState;
      this.generation = generation;
    }

    boolean isCurrent() {
      synchronized (WebBoardUpdateQueue.this) {
        return !closed && generation == boardGeneration;
      }
    }
  }
}
