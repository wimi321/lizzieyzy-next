package featurecat.lizzie.analysis;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One deadline worker; command ownership and response settlement remain in the engine queue. */
final class GtpResponseTimeoutScheduler {
  private GtpResponseTimeoutScheduler() {}

  static ScheduledFuture<?> schedule(
      ScheduledExecutorService scheduler,
      Executor commandExecutor,
      Runnable expiration,
      long delayMillis) {
    // Expiration can dispatch transport IO; it must never block deadlines for another engine.
    return scheduler.schedule(
        () -> commandExecutor.execute(expiration), delayMillis, TimeUnit.MILLISECONDS);
  }

  static ScheduledThreadPoolExecutor create() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "lizzie-gtp-response-timeouts");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setKeepAliveTime(30, TimeUnit.SECONDS);
    scheduler.allowCoreThreadTimeOut(true);
    return scheduler;
  }
}
