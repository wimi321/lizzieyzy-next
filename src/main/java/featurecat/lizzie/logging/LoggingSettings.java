package featurecat.lizzie.logging;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LoggingSettings {
  public static final String CONFIG_KEY = "logging";
  public static final String DIAGNOSTICS_ENABLED_KEY = "diagnostics-enabled";
  public static final String DIAGNOSTIC_MODULES_KEY = "diagnostic-modules";
  public static final String PREFERRED_FULL_TRACE_SCOPES_KEY = "preferred-full-trace-scopes";

  private final boolean diagnosticsEnabled;
  private final Set<DiagnosticModule> diagnosticModules;
  private final Set<TraceScope> preferredTraceScopes;

  public LoggingSettings(
      boolean diagnosticsEnabled,
      Set<DiagnosticModule> diagnosticModules,
      Set<TraceScope> preferredTraceScopes) {
    this.diagnosticsEnabled = diagnosticsEnabled;
    this.diagnosticModules =
        Collections.unmodifiableSet(
            EnumSet.copyOf(requireSet(diagnosticModules, "diagnosticModules")));
    this.preferredTraceScopes =
        Collections.unmodifiableSet(
            EnumSet.copyOf(requireSet(preferredTraceScopes, "preferredTraceScopes")));
  }

  public static LoggingSettings defaults() {
    return new LoggingSettings(
        true, EnumSet.allOf(DiagnosticModule.class), EnumSet.allOf(TraceScope.class));
  }

  public static LoggingSettings fromJson(JSONObject json) {
    if (json == null) {
      return defaults();
    }
    EnumSet<DiagnosticModule> modules = EnumSet.noneOf(DiagnosticModule.class);
    JSONArray moduleNames = json.optJSONArray(DIAGNOSTIC_MODULES_KEY);
    if (moduleNames == null) {
      modules = EnumSet.allOf(DiagnosticModule.class);
    } else {
      for (int i = 0; i < moduleNames.length(); i++) {
        modules.add(DiagnosticModule.fromWireName(moduleNames.getString(i)));
      }
    }
    EnumSet<TraceScope> scopes = EnumSet.noneOf(TraceScope.class);
    JSONArray scopeNames = json.optJSONArray(PREFERRED_FULL_TRACE_SCOPES_KEY);
    if (scopeNames == null) {
      scopes = EnumSet.allOf(TraceScope.class);
    } else {
      for (int i = 0; i < scopeNames.length(); i++) {
        scopes.add(TraceScope.fromWireName(scopeNames.getString(i)));
      }
    }
    return new LoggingSettings(json.optBoolean(DIAGNOSTICS_ENABLED_KEY, true), modules, scopes);
  }

  public boolean diagnosticsEnabled() {
    return diagnosticsEnabled;
  }

  public Set<DiagnosticModule> diagnosticModules() {
    return diagnosticModules;
  }

  public Set<TraceScope> preferredTraceScopes() {
    return preferredTraceScopes;
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put(DIAGNOSTICS_ENABLED_KEY, diagnosticsEnabled);
    JSONArray modules = new JSONArray();
    for (DiagnosticModule module : diagnosticModules) {
      modules.put(module.wireName());
    }
    json.put(DIAGNOSTIC_MODULES_KEY, modules);
    JSONArray scopes = new JSONArray();
    for (TraceScope scope : preferredTraceScopes) {
      scopes.put(scope.wireName());
    }
    json.put(PREFERRED_FULL_TRACE_SCOPES_KEY, scopes);
    return json;
  }

  public LoggingSettings withDiagnosticsEnabled(boolean enabled) {
    return new LoggingSettings(enabled, diagnosticModules, preferredTraceScopes);
  }

  public LoggingSettings withDiagnosticModules(Set<DiagnosticModule> modules) {
    return new LoggingSettings(diagnosticsEnabled, modules, preferredTraceScopes);
  }

  public LoggingSettings withPreferredTraceScopes(Set<TraceScope> scopes) {
    return new LoggingSettings(diagnosticsEnabled, diagnosticModules, scopes);
  }

  private static <T extends Enum<T>> Set<T> requireSet(Set<T> values, String name) {
    return Objects.requireNonNull(values, name);
  }
}
