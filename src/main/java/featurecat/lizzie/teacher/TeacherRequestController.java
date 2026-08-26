package featurecat.lizzie.teacher;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Owns at most one AI commentary request and prevents late callbacks from older requests. */
final class TeacherRequestController implements AutoCloseable {
  interface Listener {
    void onText(String text);

    void onComplete(String fullText);

    void onFailure(Throwable error);

    void onCancelled();
  }

  private final ExecutorService executor;
  private final AtomicLong generation = new AtomicLong();
  private TeacherLlmClient.Cancellation cancellation;
  private Future<?> future;

  TeacherRequestController() {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "lizzie-ai-commentary");
          thread.setDaemon(true);
          return thread;
        };
    executor = Executors.newSingleThreadExecutor(factory);
  }

  synchronized void start(
      TeacherLlmClient client, List<TeacherLlmClient.Message> messages, Listener listener) {
    long requestGeneration = generation.incrementAndGet();
    cancelLocked(false);
    TeacherLlmClient.Cancellation requestCancellation = new TeacherLlmClient.Cancellation();
    cancellation = requestCancellation;
    future =
        executor.submit(
            () -> {
              try {
                String fullText =
                    client.stream(
                        messages,
                        requestCancellation,
                        text -> {
                          if (isCurrent(requestGeneration, requestCancellation)) {
                            listener.onText(text);
                          }
                        });
                if (isCurrent(requestGeneration, requestCancellation)) {
                  listener.onComplete(fullText);
                }
              } catch (CancellationException cancelled) {
                if (generation.get() == requestGeneration) {
                  listener.onCancelled();
                }
              } catch (Throwable error) {
                if (requestCancellation.isCancelled()) {
                  if (generation.get() == requestGeneration) {
                    listener.onCancelled();
                  }
                } else if (isCurrent(requestGeneration, requestCancellation)) {
                  listener.onFailure(error);
                }
              }
            });
  }

  synchronized void cancel() {
    cancelLocked(true);
  }

  synchronized boolean isRunning() {
    return future != null && !future.isDone();
  }

  private boolean isCurrent(
      long requestGeneration, TeacherLlmClient.Cancellation requestCancellation) {
    return generation.get() == requestGeneration && !requestCancellation.isCancelled();
  }

  private void cancelLocked(boolean notifyGenerationChange) {
    if (notifyGenerationChange) {
      generation.incrementAndGet();
    }
    if (cancellation != null) {
      cancellation.cancel();
      cancellation = null;
    }
    if (future != null) {
      future.cancel(true);
      future = null;
    }
  }

  @Override
  public synchronized void close() {
    cancelLocked(true);
    executor.shutdownNow();
  }
}
