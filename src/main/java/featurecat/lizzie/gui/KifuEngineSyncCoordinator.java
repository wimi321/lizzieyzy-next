package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/** Serializes post-load engine restores and only completes the newest kifu request. */
final class KifuEngineSyncCoordinator {
  enum AttemptResult {
    COMPLETE,
    RETRY,
    PERMANENT_FAILURE
  }

  interface Request {
    // Terminal and context-change callbacks run on the EDT after the generation fence.
    boolean isCurrent();

    AttemptResult synchronize();

    default void onRetry(RuntimeException failure, int retryCount) {}

    void onSynchronized();

    default void onFailed() {}

    default void onContextChanged() {}

    default void onLocalPrimaryRestart(
        Leelaz primary, long previousGeneration, EngineManager manager, long switchToken) {}
  }

  private static final long INITIAL_RETRY_DELAY_MILLIS = 250L;
  private static final long MAX_RETRY_DELAY_MILLIS = 5_000L;

  private final ScheduledExecutorService executor;
  private final AtomicLong generation = new AtomicLong();
  private volatile Request currentRequest;

  KifuEngineSyncCoordinator() {
    this(
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "lizzie-kifu-engine-sync");
              thread.setDaemon(true);
              return thread;
            }));
  }

  KifuEngineSyncCoordinator(ScheduledExecutorService executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  synchronized void submit(Request request) {
    Objects.requireNonNull(request, "request");
    long requestGeneration = generation.incrementAndGet();
    currentRequest = request;
    executor.execute(() -> run(requestGeneration, request, 0));
  }

  synchronized void onLocalPrimaryRestart(
      Leelaz primary, long previousGeneration, EngineManager manager, long switchToken) {
    long requestGeneration = generation.get();
    Request request = currentRequest;
    if (request == null) return;
    SwingUtilities.invokeLater(
        () -> {
          if (requestGeneration == generation.get() && request == currentRequest) {
            request.onLocalPrimaryRestart(primary, previousGeneration, manager, switchToken);
          }
        });
  }

  synchronized void cancel() {
    generation.incrementAndGet();
    currentRequest = null;
  }

  void close() {
    cancel();
    executor.shutdownNow();
  }

  private void run(long requestGeneration, Request request, int retryCount) {
    if (!isCurrent(requestGeneration, request)) {
      return;
    }
    AttemptResult result;
    try {
      result = request.synchronize();
    } catch (RuntimeException failure) {
      request.onRetry(failure, retryCount);
      result = AttemptResult.RETRY;
    }
    if (!isCurrent(requestGeneration, request)) {
      return;
    }
    if (result == AttemptResult.COMPLETE) {
      SwingUtilities.invokeLater(
          () -> {
            if (isCurrent(requestGeneration, request)) request.onSynchronized();
          });
      return;
    }
    if (result == AttemptResult.PERMANENT_FAILURE) {
      SwingUtilities.invokeLater(
          () -> {
            if (isCurrent(requestGeneration, request)) request.onFailed();
          });
      return;
    }
    long retryDelay = retryDelayMillis(retryCount);
    executor.schedule(
        () -> run(requestGeneration, request, retryCount + 1), retryDelay, TimeUnit.MILLISECONDS);
  }

  private boolean isCurrent(long requestGeneration, Request request) {
    if (requestGeneration != generation.get()) {
      return false;
    }
    if (!request.isCurrent()) {
      Runnable contextChanged =
          () -> {
            if (requestGeneration == generation.get() && !request.isCurrent()) {
              request.onContextChanged();
            }
          };
      if (SwingUtilities.isEventDispatchThread()) contextChanged.run();
      else SwingUtilities.invokeLater(contextChanged);
      return false;
    }
    return true;
  }

  private static long retryDelayMillis(int retryCount) {
    int shift = Math.min(5, Math.max(0, retryCount));
    return Math.min(MAX_RETRY_DELAY_MILLIS, INITIAL_RETRY_DELAY_MILLIS << shift);
  }
}
