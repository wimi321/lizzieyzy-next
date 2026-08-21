package featurecat.lizzie.logging;

import java.net.URI;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NetworkObservation {
  private static final Logger NETWORK = LoggerFactory.getLogger(LogCategories.NETWORK);
  private static final Logger REMOTE = LoggerFactory.getLogger(LogCategories.REMOTE);
  private static final Logger TRACE = LoggerFactory.getLogger(LogCategories.NETWORK_TRACE);

  private NetworkObservation() {}

  public static boolean diagnosticsEnabled() {
    return initialized() && (NETWORK.isDebugEnabled() || REMOTE.isDebugEnabled());
  }

  public static boolean traceEnabled() {
    return initialized()
        && LoggingRuntime.current().filter(LoggingRuntime::fullTraceActive).isPresent()
        && TRACE.isInfoEnabled();
  }

  public static String newRequestIdentity() {
    return LoggingRuntime.current()
        .filter(runtime -> !runtime.isShutdown())
        .map(LoggingRuntime::newRequestIdentity)
        .orElse("req-none");
  }

  public static void recordNetwork(
      String method,
      String host,
      NetworkEndpointCategory category,
      Integer status,
      long latencyMs,
      String outcome,
      String requestId) {
    record(NETWORK, "network", method, host, category, status, latencyMs, outcome, requestId);
  }

  public static void recordRemote(
      String method,
      String host,
      NetworkEndpointCategory category,
      Integer status,
      long latencyMs,
      String outcome,
      String requestId) {
    record(REMOTE, "remote", method, host, category, status, latencyMs, outcome, requestId);
  }

  public static void tracePayload(
      NetworkEndpointCategory category, String direction, Supplier<String> payload) {
    if (category == null || category.prohibitsBodies() || payload == null) {
      return;
    }
    if (!traceEnabled()) {
      return;
    }
    String text = payload.get();
    if (text == null) {
      return;
    }
    inContext(
        null,
        () ->
            TRACE.info(
                "network raw direction={} payload={}",
                direction == null || direction.isEmpty() ? "unknown" : direction,
                text));
  }

  public static void inContext(String requestId, Runnable action) {
    if (action == null) {
      return;
    }
    String trace = LoggingRuntime.current().map(LoggingRuntime::currentTraceSessionId).orElse(null);
    try {
      CorrelationContext.installRequest(requestId);
      if (trace != null) {
        CorrelationContext.installTraceSession(trace);
      }
      action.run();
    } finally {
      CorrelationContext.clearRequest();
      CorrelationContext.clearTraceSession();
    }
  }

  private static void record(
      Logger logger,
      String kind,
      String method,
      String host,
      NetworkEndpointCategory category,
      Integer status,
      long latencyMs,
      String outcome,
      String requestId) {
    if (!initialized()) {
      return;
    }
    String safeOutcome = outcome == null || outcome.isEmpty() ? "unknown" : outcome;
    boolean failed = "failed".equals(safeOutcome) || "error".equals(safeOutcome);
    if (failed) {
      if (!logger.isWarnEnabled()) {
        return;
      }
    } else if (!(initialized() && logger.isDebugEnabled())) {
      return;
    }
    String safeMethod = method == null || method.isEmpty() ? "UNKNOWN" : method;
    String safeHost = safeHost(host);
    String safeCategory =
        category == null ? NetworkEndpointCategory.OTHER.wireName() : category.wireName();
    Object safeStatus = status == null ? "-" : status;
    inContext(
        requestId,
        () -> {
          if (failed) {
            logger.warn(
                "{} event=http method={} host={} category={} status={} latencyMs={} outcome={}",
                kind,
                safeMethod,
                safeHost,
                safeCategory,
                safeStatus,
                latencyMs,
                safeOutcome);
          } else {
            logger.debug(
                "{} event=http method={} host={} category={} status={} latencyMs={} outcome={}",
                kind,
                safeMethod,
                safeHost,
                safeCategory,
                safeStatus,
                latencyMs,
                safeOutcome);
          }
        });
  }

  static String safeHost(String host) {
    if (host == null || host.isEmpty()) {
      return "unknown";
    }
    if (!host.contains("://") && host.indexOf('/') < 0 && host.indexOf('?') < 0) {
      return host;
    }
    try {
      URI uri = host.contains("://") ? URI.create(host) : URI.create("https://" + host);
      String parsed = uri.getHost();
      return parsed == null || parsed.isEmpty() ? "unknown" : parsed;
    } catch (RuntimeException ignored) {
      return "unknown";
    }
  }

  private static boolean initialized() {
    return LoggingRuntime.current().filter(runtime -> !runtime.isShutdown()).isPresent();
  }
}
