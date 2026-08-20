package featurecat.lizzie.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.status.ErrorStatus;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class BoundedAsyncAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
  private final LogStream stream;
  private final BlockingQueue<ILoggingEvent> queue;
  private final boolean dropInfoFirst;
  private final int discardingThreshold;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong inFlight = new AtomicLong();
  private final AtomicLong abandoned = new AtomicLong();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean workerFailed = new AtomicBoolean();
  private volatile Appender<ILoggingEvent> nested;
  private volatile LoggingRuntime runtime;
  private volatile CountDownLatch gate;
  private volatile boolean failWrites;
  private volatile long nestedStopPauseMillis;
  private volatile Path activeFile;
  private Thread worker;

  BoundedAsyncAppender(LogStream stream, int capacity, boolean dropInfoFirst) {
    this.stream = stream;
    this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    this.dropInfoFirst = dropInfoFirst;
    this.discardingThreshold = Math.max(1, capacity / 4);
  }

  void setNested(Appender<ILoggingEvent> nested) {
    this.nested = nested;
  }

  void setRuntime(LoggingRuntime runtime) {
    this.runtime = runtime;
  }

  void setGate(CountDownLatch gate) {
    this.gate = gate;
  }

  void setFailWrites(boolean failWrites) {
    this.failWrites = failWrites;
  }

  void setNestedStopPauseMillis(long nestedStopPauseMillis) {
    this.nestedStopPauseMillis = nestedStopPauseMillis;
  }

  boolean isNestedStartedForTests() {
    Appender<ILoggingEvent> current = nested;
    return current != null && current.isStarted();
  }

  void setActiveFile(Path activeFile) {
    this.activeFile = activeFile;
  }

  LogStream stream() {
    return stream;
  }

  long droppedCount() {
    return dropped.get();
  }

  int queuedCount() {
    return queue.size();
  }

  void stopAccepting() {
    accepting.set(false);
  }

  @Override
  public void start() {
    if (nested == null) {
      addError("Nested appender is required");
      return;
    }
    if (!nested.isStarted()) {
      nested.start();
    }
    worker = new Thread(this::drain, "lizzie-log-" + stream.name().toLowerCase());
    worker.setDaemon(true);
    super.start();
    worker.start();
  }

  @Override
  public void stop() {
    accepting.set(false);
    super.stop();
    if (worker != null) {
      worker.interrupt();
    }
  }

  long shutdown(long deadlineNanos) {
    accepting.set(false);
    if (worker != null) {
      worker.interrupt();
      joinUntil(deadlineNanos);
    }
    if (nested != null && nested.isStarted()) {
      Deadline.run(
          deadlineNanos,
          () -> {
            long pause = nestedStopPauseMillis;
            if (pause > 0) {
              try {
                Thread.sleep(pause);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            nested.stop();
          });
      if (nested.isStarted()) {
        nested.stop();
      }
    }
    super.stop();
    return queue.size() + inFlight.get() + abandoned.get();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (!accepting.get() || event == null) {
      if (event != null) {
        dropped.incrementAndGet();
        notifyDrop();
      }
      return;
    }
    event.prepareForDeferredProcessing();
    if (dropInfoFirst
        && queue.remainingCapacity() <= discardingThreshold
        && event.getLevel().toInt() < Level.WARN_INT) {
      dropped.incrementAndGet();
      notifyDrop();
      return;
    }
    if (!queue.offer(event)) {
      dropped.incrementAndGet();
      notifyDrop();
    }
  }

  private void drain() {
    while (true) {
      try {
        ILoggingEvent event = queue.poll(50, TimeUnit.MILLISECONDS);
        if (event == null) {
          if (!accepting.get()) {
            break;
          }
          continue;
        }
        inFlight.incrementAndGet();
        boolean completedAppend = false;
        try {
          CountDownLatch currentGate = gate;
          if (currentGate != null) {
            currentGate.await();
          }
          long generation = runtime == null ? 0L : runtime.failureGeneration(stream);
          if (failWrites) {
            reportWriteFailure();
          } else {
            nested.doAppend(event);
          }
          completedAppend = true;
          if (runtime != null
              && nested.isStarted()
              && runtime.failureGeneration(stream) == generation) {
            runtime.recordSuccess(stream);
          }
        } finally {
          if (!completedAppend) {
            abandoned.incrementAndGet();
          }
          inFlight.decrementAndGet();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        if (workerFailed.compareAndSet(false, true) && runtime != null) {
          runtime.recordFailure(stream, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }
    }
  }

  private void reportWriteFailure() {
    String path = activeFile == null ? stream.name() : activeFile.toAbsolutePath().toString();
    ErrorStatus status =
        new ErrorStatus("IO failure while writing to file [" + path + "]", nested);
    if (getContext() != null) {
      getContext().getStatusManager().add(status);
    } else if (runtime != null) {
      runtime.recordFailure(stream, status.getMessage());
    }
  }

  private void joinUntil(long deadlineNanos) {
    if (worker == null) {
      return;
    }
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      return;
    }
    try {
      worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void notifyDrop() {
    LoggingRuntime current = runtime;
    if (current != null) {
      current.recordDrop(stream);
    }
  }
}
