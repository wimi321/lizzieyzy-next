package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ConfigFirstLaunchDefaultsTest {
  @Test
  void freshProfileStartsInTheSupportedSystemLanguageWithoutAutomaticBenchmark() {
    JSONObject ui =
        new JSONObject().put("use-language", 4).put("enable-startup-benchmark", true);

    Config.applyFirstLaunchDefaults(ui, true, Locale.JAPAN);

    assertEquals(AppLocale.JAPANESE.configValue(), ui.getInt("use-language"));
    assertFalse(ui.getBoolean("enable-startup-benchmark"));
  }

  @Test
  void freshProfileFallsBackToSimplifiedChineseForUnsupportedSystemLanguage() {
    JSONObject ui = new JSONObject().put("enable-startup-benchmark", true);

    Config.applyFirstLaunchDefaults(ui, true, Locale.FRANCE);

    assertEquals(AppLocale.SIMPLIFIED_CHINESE.configValue(), ui.getInt("use-language"));
    assertFalse(ui.getBoolean("enable-startup-benchmark"));
  }

  @Test
  void existingProfileKeepsLanguageAndBenchmarkPreference() {
    JSONObject ui =
        new JSONObject().put("use-language", 6).put("enable-startup-benchmark", true);

    Config.applyFirstLaunchDefaults(ui, false, Locale.JAPAN);

    assertEquals(AppLocale.THAI.configValue(), ui.getInt("use-language"));
    assertTrue(ui.getBoolean("enable-startup-benchmark"));
  }
}
