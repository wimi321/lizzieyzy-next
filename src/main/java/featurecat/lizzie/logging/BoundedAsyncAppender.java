package featurecat.lizzie.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.status.ErrorStatus;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class BoundedAsyncAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
  private static final class QueuedEvent {
    final long sequence;
    final long incidentGeneration;
    final ILoggingEvent event;

    QueuedEvent(long sequence, long incidentGeneration, ILoggingEvent event) {
      this.sequence = sequence;
      this.incidentGeneration = incidentGeneration;
      this.event = event;
    }
  }

  private final LogStream stream;
  private final BlockingQueue<QueuedEvent> queue;
  private final boolean dropInfoFirst;
  private final int discardingThreshold;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong inFlight = new AtomicLong();
  private final AtomicLong nextSequence = new AtomicLong();
  private final AtomicLong published = new AtomicLong();
  private final AtomicLong consecutiveCompleted = new AtomicLong();
  private final Set<Long> completedOutOfOrder = new HashSet<>();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean workerFailed = new AtomicBoolean();
  private final AtomicBoolean beforeAcceptHoldArmed = new AtomicBoolean();
  private final AtomicBoolean afterAcceptHoldArmed = new AtomicBoolean();
  // Linearizes the shutdown transition with queue admission and publication of its watermark.
  private final Object acceptanceLock = new Object();
  // Keeps completion, outstanding, and abandoned accounting as one consistent snapshot.
  private final Object progress = new Object();
  private long outstanding;
  private long abandoned;
  private volatile Appender<ILoggingEvent> nested;
  private volatile LoggingRuntime runtime;
  private volatile CountDownLatch gate;
  private volatile CountDownLatch beforeAcceptEntered;
  private volatile CountDownLatch beforeAcceptHold;
  private volatile CountDownLatch handoffEntered;
  private volatile CountDownLatch handoffHold;
  private volatile CountDownLatch afterAcceptEntered;
  private volatile CountDownLatch afterAcceptHold;
  private volatile boolean failWrites;
  private volatile long nestedStopPauseMillis;
  private volatile long nestedAppendDelayMillis;
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

  boolean isWorkerAliveForTests() {
    Thread current = worker;
    return current != null && current.isAlive();
  }

  void setNestedAppendDelayMillis(long nestedAppendDelayMillis) {
    this.nestedAppendDelayMillis = nestedAppendDelayMillis;
  }

  void setHandoffForTests(CountDownLatch entered, CountDownLatch hold) {
    this.handoffEntered = entered;
    this.handoffHold = hold;
  }

  void setBeforeAcceptHoldForTests(CountDownLatch entered, CountDownLatch hold) {
    this.beforeAcceptEntered = entered;
    this.beforeAcceptHold = hold;
    this.beforeAcceptHoldArmed.set(true);
  }

  void setAfterAcceptHoldForTests(CountDownLatch entered, CountDownLatch hold) {
    this.afterAcceptEntered = entered;
    this.afterAcceptHold = hold;
    this.afterAcceptHoldArmed.set(true);
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

  long inFlightCount() {
    return inFlight.get();
  }

  long outstandingCount() {
    synchronized (progress) {
      return outstanding;
    }
  }

  int completionBookkeepingSize() {
    synchronized (progress) {
      return completedOutOfOrder.size();
    }
  }

  void stopAccepting() {
    synchronized (acceptanceLock) {
      accepting.set(false);
    }
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
    stopAccepting();
    super.stop();
    interruptWorker();
  }

  void awaitWorkerUntil(long deadlineNanos) {
    joinUntil(deadlineNanos);
  }

  void interruptWorker() {
    Thread current = worker;
    if (current != null && current.isAlive()) {
      current.interrupt();
    }
  }

  void stopNested() {
    boolean interrupted = false;
    long pause = nestedStopPauseMillis;
    if (pause > 0) {
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    Appender<ILoggingEvent> current = nested;
    if (current != null && current.isStarted()) {
      current.stop();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  void finishShutdown() {
    super.stop();
  }

  long unwrittenCount() {
    synchronized (progress) {
      return outstanding + abandoned;
    }
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
    awaitBeforeAcceptHoldForTests();
    boolean rejected = false;
    synchronized (acceptanceLock) {
      if (!accepting.get()) {
        rejected = true;
      } else if (dropInfoFirst
          && queue.remainingCapacity() <= discardingThreshold
          && event.getLevel().toInt() < Level.WARN_INT) {
        rejected = true;
      } else if (queue.remainingCapacity() == 0) {
        rejected = true;
      } else {
        long sequence = nextSequence.get() + 1L;
        long incidentGeneration =
            runtime == null ? 0L : runtime.incidentGeneration(stream);
        QueuedEvent queued = new QueuedEvent(sequence, incidentGeneration, event);
        synchronized (progress) {
          outstanding++;
        }
        if (!queue.offer(queued)) {
          synchronized (progress) {
            outstanding--;
          }
          rejected = true;
        } else {
          nextSequence.set(sequence);
          publishSequence(sequence);
        }
      }
    }
    if (rejected) {
      dropped.incrementAndGet();
      notifyDrop();
      return;
    }
    awaitAfterAcceptHoldForTests();
  }

  private void drain() {
    while (true) {
      QueuedEvent queued = null;
      try {
        queued = queue.poll(50, TimeUnit.MILLISECONDS);
        if (queued == null) {
          if (!accepting.get()) {
            break;
          }
          continue;
        }
        inFlight.incrementAndGet();
        CountDownLatch entered = handoffEntered;
        if (entered != null) {
          entered.countDown();
        }
        CountDownLatch hold = handoffHold;
        if (hold != null) {
          hold.await();
        }
        boolean persisted = false;
        try {
          long delay = nestedAppendDelayMillis;
          if (delay > 0) {
            Thread.sleep(delay);
          }
          CountDownLatch currentGate = gate;
          if (currentGate != null) {
            currentGate.await();
          }
          long generation = runtime == null ? 0L : runtime.failureGeneration(stream);
          if (failWrites) {
            reportWriteFailure();
          } else {
            nested.doAppend(queued.event);
          }
          persisted =
              nested.isStarted()
                  && (runtime == null || runtime.failureGeneration(stream) == generation);
          if (persisted && runtime != null) {
            runtime.recordSuccess(stream, queued.incidentGeneration);
          }
        } finally {
          inFlight.decrementAndGet();
          completeSequence(queued.sequence, persisted);
          queued = null;
        }
      } catch (InterruptedException e) {
        if (queued != null) {
          inFlight.decrementAndGet();
          completeSequence(queued.sequence, false);
        }
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        if (workerFailed.compareAndSet(false, true) && runtime != null) {
          runtime.recordFailure(stream, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }
    }
  }

  long submittedCount() {
    synchronized (acceptanceLock) {
      return published.get();
    }
  }

  long completedCount() {
    return consecutiveCompleted.get();
  }

  boolean awaitSubmitted(long mark, long deadlineNanos) {
    boolean interrupted = Thread.interrupted();
    try {
      while (consecutiveCompleted.get() < mark) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
          return consecutiveCompleted.get() >= mark;
        }
        synchronized (progress) {
          if (consecutiveCompleted.get() >= mark) {
            return true;
          }
          try {
            progress.wait(Math.max(1L, Math.min(50L, TimeUnit.NANOSECONDS.toMillis(remaining))));
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      return true;
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void publishSequence(long sequence) {
    published.accumulateAndGet(sequence, Math::max);
  }

  private void completeSequence(long sequence, boolean persisted) {
    synchronized (progress) {
      outstanding--;
      if (!persisted) {
        abandoned++;
      }
      long cursor = consecutiveCompleted.get();
      if (sequence == cursor + 1) {
        cursor++;
        while (completedOutOfOrder.remove(cursor + 1)) {
          cursor++;
        }
        consecutiveCompleted.set(cursor);
      } else if (sequence > cursor + 1) {
        completedOutOfOrder.add(sequence);
      }
      progress.notifyAll();
    }
  }

  private void awaitBeforeAcceptHoldForTests() {
    if (!beforeAcceptHoldArmed.compareAndSet(true, false)) {
      return;
    }
    CountDownLatch entered = beforeAcceptEntered;
    if (entered != null) {
      entered.countDown();
    }
    CountDownLatch hold = beforeAcceptHold;
    if (hold != null) {
      try {
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void awaitAfterAcceptHoldForTests() {
    if (!afterAcceptHoldArmed.compareAndSet(true, false)) {
      return;
    }
    CountDownLatch entered = afterAcceptEntered;
    if (entered != null) {
      entered.countDown();
    }
    CountDownLatch hold = afterAcceptHold;
    if (hold != null) {
      try {
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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
