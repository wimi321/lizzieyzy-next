package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.util.List;
import org.json.JSONObject;

/**
 * Engine-vs-engine black/white picks are stored as engine identity (command + name), not ComboBox
 * index, so reordering the engine list cannot restore a different slot.
 */
public final class EnginePkIdentity {
  public static final String BLACK_COMMANDS_KEY = "engine-pk-black-commands";
  public static final String WHITE_COMMANDS_KEY = "engine-pk-white-commands";
  public static final String BLACK_NAME_KEY = "engine-pk-black-name";
  public static final String WHITE_NAME_KEY = "engine-pk-white-name";

  private EnginePkIdentity() {}

  public static int resolveIndex(List<EngineData> engines, String commands, String name) {
    if (engines == null || engines.isEmpty()) {
      return -1;
    }
    if (commands == null || commands.isEmpty()) {
      return 0;
    }
    int commandMatch = -1;
    for (int i = 0; i < engines.size(); i++) {
      EngineData engine = engines.get(i);
      if (engine == null || !commands.equals(engine.commands)) {
        continue;
      }
      if (commandMatch < 0) {
        commandMatch = i;
      }
      if (name != null && name.equals(engine.name)) {
        return i;
      }
    }
    return commandMatch >= 0 ? commandMatch : 0;
  }

  public static int[] restoreIndexes(List<EngineData> engines, JSONObject uiConfig) {
    return new int[] {
      resolveIndex(
          engines, optString(uiConfig, BLACK_COMMANDS_KEY), optString(uiConfig, BLACK_NAME_KEY)),
      resolveIndex(
          engines, optString(uiConfig, WHITE_COMMANDS_KEY), optString(uiConfig, WHITE_NAME_KEY))
    };
  }

  public static void persistSelection(
      JSONObject uiConfig, List<EngineData> engines, int blackIndex, int whiteIndex) {
    if (uiConfig == null) {
      return;
    }
    persistSide(uiConfig, true, engineAt(engines, blackIndex));
    persistSide(uiConfig, false, engineAt(engines, whiteIndex));
  }

  public static void persistSelection(
      Config config, List<EngineData> engines, int blackIndex, int whiteIndex) {
    if (config == null) {
      return;
    }
    persistSelection(config.uiConfig, engines, blackIndex, whiteIndex);
  }

  public static void persistSide(
      Config config, List<EngineData> engines, boolean black, int index) {
    if (config == null || config.uiConfig == null) {
      return;
    }
    persistSide(config.uiConfig, black, engineAt(engines, index));
  }

  public static void restoreToolbarSelection(
      List<EngineData> engines, Config config, BottomToolbar toolbar) {
    if (toolbar == null) {
      return;
    }
    JSONObject uiConfig = config == null ? null : config.uiConfig;
    int[] indexes = restoreIndexes(engines, uiConfig);
    toolbar.engineBlackToolbar = selectCombo(toolbar.enginePkBlack, indexes[0]);
    toolbar.engineWhiteToolbar = selectCombo(toolbar.enginePkWhite, indexes[1]);
  }

  private static int selectCombo(javax.swing.JComboBox<?> combo, int index) {
    if (combo == null || combo.getItemCount() == 0) {
      return 0;
    }
    int selected = index;
    if (selected < 0 || selected >= combo.getItemCount()) {
      selected = 0;
    }
    combo.setSelectedIndex(selected);
    return selected;
  }

  private static void persistSide(JSONObject uiConfig, boolean black, EngineData engine) {
    if (engine == null) {
      return;
    }
    uiConfig.put(black ? BLACK_COMMANDS_KEY : WHITE_COMMANDS_KEY, nullToEmpty(engine.commands));
    uiConfig.put(black ? BLACK_NAME_KEY : WHITE_NAME_KEY, nullToEmpty(engine.name));
  }

  private static EngineData engineAt(List<EngineData> engines, int index) {
    if (engines == null || index < 0 || index >= engines.size()) {
      return null;
    }
    return engines.get(index);
  }

  private static String optString(JSONObject uiConfig, String key) {
    if (uiConfig == null) {
      return "";
    }
    return uiConfig.optString(key, "");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
