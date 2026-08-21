package featurecat.lizzie.logging;

import org.slf4j.MDC;

public final class CorrelationContext {
  public static final String APP_SESSION = "lizzie.appSession";
  public static final String ENGINE_ID = "lizzie.engineId";
  public static final String COMMAND_ID = "lizzie.commandId";
  public static final String TRACE_SESSION = "lizzie.traceSession";
  public static final String GMA_ID = "lizzie.gmaId";
  public static final String SYNC_SESSION = "lizzie.syncSession";

  private CorrelationContext() {}

  public static void installAppSession(String applicationLogSessionId) {
    put(APP_SESSION, applicationLogSessionId);
  }

  public static void installEngine(String engineId) {
    put(ENGINE_ID, engineId);
  }

  public static void installCommand(String commandId) {
    put(COMMAND_ID, commandId);
  }

  public static void installTraceSession(String traceSessionId) {
    put(TRACE_SESSION, traceSessionId);
  }

  public static void installGma(String gmaId) {
    put(GMA_ID, gmaId);
  }

  public static void installSyncSession(String sessionId) {
    put(SYNC_SESSION, sessionId);
  }

  public static void clearEngine() {
    MDC.remove(ENGINE_ID);
  }

  public static void clearCommand() {
    MDC.remove(COMMAND_ID);
  }

  public static void clearTraceSession() {
    MDC.remove(TRACE_SESSION);
  }

  public static void clearGma() {
    MDC.remove(GMA_ID);
  }

  public static void clearSyncSession() {
    MDC.remove(SYNC_SESSION);
  }

  public static void clearAsync() {
    MDC.remove(ENGINE_ID);
    MDC.remove(COMMAND_ID);
    MDC.remove(TRACE_SESSION);
    MDC.remove(GMA_ID);
    MDC.remove(SYNC_SESSION);
  }

  private static void put(String key, String value) {
    if (value == null || value.isEmpty()) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }
}
