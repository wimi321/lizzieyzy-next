package featurecat.lizzie.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial background writes; completion effects always run on the supplied owner executor. */
final class SgfSaveQueue {
  @FunctionalInterface
  interface Writer {
    void write(Path path, String snapshot) throws IOException;
  }

  private final Executor worker;
  private final Executor owner;
  private final Writer writer;
  private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

  SgfSaveQueue(Executor worker, Executor owner, Writer writer) {
    this.worker = worker;
    this.owner = owner;
    this.writer = writer;
  }

  synchronized CompletableFuture<Void> submit(
      Path path,
      String snapshot,
      BooleanSupplier stillCurrent,
      Runnable success,
      Consumer<Throwable> failure) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    pending = CompletableFuture.allOf(pending, completion).handle((ignored, error) -> null);
    Runnable write =
        () -> {
          Throwable error = null;
          try {
            writer.write(path, snapshot);
          } catch (IOException | RuntimeException caught) {
            error = caught;
          }
          Throwable result = error;
          owner.execute(() -> finish(stillCurrent, success, failure, completion, result));
        };
    try {
      worker.execute(write);
    } catch (RuntimeException rejected) {
      owner.execute(() -> finish(stillCurrent, success, failure, completion, rejected));
    }
    return completion;
  }

  private void finish(
      BooleanSupplier stillCurrent,
      Runnable success,
      Consumer<Throwable> failure,
      CompletableFuture<Void> completion,
      Throwable error) {
    try {
      if (error != null) {
        failure.accept(error);
        completion.completeExceptionally(error);
      } else {
        if (stillCurrent.getAsBoolean()) success.run();
        completion.complete(null);
      }
    } catch (RuntimeException callbackFailure) {
      completion.completeExceptionally(callbackFailure);
    }
  }

  synchronized CompletableFuture<Void> pending() {
    return pending;
  }
}
