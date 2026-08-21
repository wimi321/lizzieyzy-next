package featurecat.lizzie.logging;

import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

public final class DiagnosticBundleRequest {
  private final LoggingRuntime runtime;
  private final Set<TraceScope> rawScopes;
  private final JSONObject config;
  private final SyncDiagnosticsExportSnapshot snapshot;
  private final String appVersion;

  public DiagnosticBundleRequest(
      LoggingRuntime runtime,
      Set<TraceScope> rawScopes,
      JSONObject config,
      SyncDiagnosticsExportSnapshot snapshot,
      String appVersion) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.rawScopes =
        rawScopes == null || rawScopes.isEmpty()
            ? EnumSet.noneOf(TraceScope.class)
            : EnumSet.copyOf(rawScopes);
    this.config = config == null ? new JSONObject() : new JSONObject(config.toString());
    this.snapshot = snapshot;
    this.appVersion = appVersion == null ? "unknown" : appVersion;
  }

  public LoggingRuntime runtime() {
    return runtime;
  }

  public Set<TraceScope> rawScopes() {
    return Collections.unmodifiableSet(rawScopes);
  }

  public JSONObject config() {
    return config;
  }

  public SyncDiagnosticsExportSnapshot snapshot() {
    return snapshot;
  }

  public String appVersion() {
    return appVersion;
  }
}
