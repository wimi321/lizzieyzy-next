package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatGptReasoningLabelsTest {
  @ParameterizedTest
  @ValueSource(strings = {"en-US", "zh-CN", "zh-TW", "zh-HK", "ja-JP", "ko", "th-TH"})
  void localizedLabelsKeepCodexEffortIdentity(String locale) {
    var previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(locale));
      for (String value :
          List.of(
              "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "persistent")) {
        String label = new ChatGptSettingsPanel.Effort(value).toString();
        assertTrue(label.equalsIgnoreCase(value) || label.endsWith("(" + value + ")"), label);
        assertFalse(label.contains("Teacher."));
      }
      assertFalse(new ChatGptSettingsPanel.Effort("").toString().contains("()"));
      assertEquals("future-depth", new ChatGptSettingsPanel.Effort("future-depth").toString());
      if (locale.equals("zh-CN")) {
        assertEquals(
            List.of("低 (low)", "中 (medium)", "高 (high)", "超高 (xhigh)", "最大 (max)", "极限 (ultra)"),
            List.of("low", "medium", "high", "xhigh", "max", "ultra").stream()
                .map(value -> new ChatGptSettingsPanel.Effort(value).toString())
                .toList());
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }
}
