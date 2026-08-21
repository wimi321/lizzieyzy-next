package featurecat.lizzie.logging;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ConfigExportProjection {
  private static final Set<String> UI_KEYS =
      Set.of(
          "board-size",
          "theme",
          "show-coordinates",
          "show-winrate-overview",
          "extra-mode",
          "is-apple-style",
          "analysis-max-visits",
          "max-game-thinking-time-seconds",
          "autoload-default",
          "network-proxy-mode");

  private ConfigExportProjection() {}

  public static JSONObject project(JSONObject config) {
    JSONObject exported = new JSONObject();
    if (config == null) {
      return exported;
    }
    JSONObject ui = config.optJSONObject("ui");
    if (ui != null) {
      JSONObject projectedUi = new JSONObject();
      for (String key : UI_KEYS) {
        if (ui.has(key)) {
          projectedUi.put(key, ui.get(key));
        }
      }
      exported.put("ui", projectedUi);
    }
    JSONObject leelaz = config.optJSONObject("leelaz");
    if (leelaz != null) {
      exported.put("leelaz", projectLeelaz(leelaz));
    }
    JSONObject logging = config.optJSONObject(LoggingSettings.CONFIG_KEY);
    if (logging != null) {
      exported.put(LoggingSettings.CONFIG_KEY, new JSONObject(logging.toString()));
    }
    return exported;
  }

  private static JSONObject projectLeelaz(JSONObject leelaz) {
    JSONObject projected = new JSONObject();
    if (leelaz.has("command")) {
      putEngineSummary(projected, leelaz.optString("command", ""));
    }
    JSONArray settings = leelaz.optJSONArray("engine-settings");
    if (settings != null) {
      JSONArray engines = new JSONArray();
      for (int i = 0; i < settings.length(); i++) {
        JSONObject engine = settings.optJSONObject(i);
        if (engine == null) {
          continue;
        }
        JSONObject summary = new JSONObject();
        if (engine.has("name")) {
          summary.put("name", engine.optString("name"));
        }
        putEngineSummary(summary, engine.optString("command", ""));
        engines.put(summary);
      }
      projected.put("engine-settings", engines);
    }
    return projected;
  }

  private static void putEngineSummary(JSONObject target, String command) {
    target.put("kind", engineKind(command));
    target.put("executable", executableBasename(command));
  }

  static String engineKind(String command) {
    String lower = command == null ? "" : command.toLowerCase(Locale.ROOT);
    if (lower.contains("katago")) {
      return "katago";
    }
    if (lower.contains("leelaz")) {
      return "leelaz";
    }
    if (lower.contains("zen")) {
      return "zen";
    }
    return "other";
  }

  static String executableBasename(String command) {
    if (command == null || command.isBlank()) {
      return "unknown";
    }
    List<String> tokens = tokenize(command.trim());
    if (tokens.isEmpty()) {
      return "unknown";
    }
    String executable = tokens.get(0);
    int separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
    return separator >= 0 ? executable.substring(separator + 1) : executable;
  }

  private static List<String> tokenize(String command) {
    java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
        continue;
      }
      if (!quoted && Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }
}
