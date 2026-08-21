package featurecat.lizzie.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashHandlers {
  private static final Logger CRASH = LoggerFactory.getLogger(LogCategories.CRASH);
  private static final Object LOCK = new Object();
  private static final ThreadLocal<Boolean> RECORDING =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  private static Thread.UncaughtExceptionHandler previous;
  private static boolean installed;

  private CrashHandlers() {}

  public static void install() {
    synchronized (LOCK) {
      if (installed) {
        return;
      }
      previous = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(CrashHandlers::handle);
      installed = true;
    }
  }

  static void resetForTests() {
    synchronized (LOCK) {
      if (installed) {
        Thread.setDefaultUncaughtExceptionHandler(previous);
      }
      previous = null;
      installed = false;
    }
    RECORDING.remove();
  }

  static void handle(Thread thread, Throwable error) {
    record(thread, error);
    Thread.UncaughtExceptionHandler next;
    synchronized (LOCK) {
      next = previous;
    }
    if (isMainThread(thread) || next != null) {
      flushForProcessExit(thread);
    }
    if (next != null) {
      next.uncaughtException(thread, error);
    } else if (error != null && !(error instanceof ThreadDeath)) {
      Thread target = thread == null ? Thread.currentThread() : thread;
      System.err.print("Exception in thread \"" + target.getName() + "\" ");
      error.printStackTrace(System.err);
    }
  }

  public static void recordFatal(Throwable error) {
    Thread thread = Thread.currentThread();
    record(thread, error);
    flushForProcessExit(thread);
  }

  public static void record(Thread thread, Throwable error) {
    if (error == null) {
      return;
    }
    if (Boolean.TRUE.equals(RECORDING.get())) {
      return;
    }
    RECORDING.set(Boolean.TRUE);
    try {
      Thread target = thread == null ? Thread.currentThread() : thread;
      LoggingRuntime.current()
          .ifPresent(
              runtime -> CorrelationContext.installAppSession(runtime.applicationLogSessionId()));
      if (CRASH.isErrorEnabled()) {
        String session =
            LoggingRuntime.current().map(LoggingRuntime::applicationLogSessionId).orElse("none");
        CRASH.error("uncaught exception thread={} session={}", target.getName(), session, error);
      }
    } catch (Throwable ignored) {
    } finally {
      RECORDING.set(Boolean.FALSE);
    }
  }

  private static boolean isMainThread(Thread thread) {
    return thread != null && "main".equals(thread.getName());
  }

  private static void flushForProcessExit(Thread thread) {
    String name = thread == null ? "" : thread.getName();
    if (name.startsWith("lizzie-log-")) {
      return;
    }
    LoggingRuntime.current().ifPresent(LoggingRuntime::awaitAppAndCrashPersistence);
  }
}
