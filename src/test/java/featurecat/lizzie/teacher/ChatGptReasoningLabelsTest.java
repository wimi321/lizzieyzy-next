package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
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
      String defaultLabel = new ChatGptSettingsPanel.Effort("", "medium").toString();
      assertTrue(defaultLabel.endsWith(new ChatGptSettingsPanel.Effort("medium").toString()));
      assertEquals("", new ChatGptSettingsPanel.Effort("", "medium").value());
      assertFalse(defaultLabel.contains("Teacher."));
      assertEquals("future-depth", new ChatGptSettingsPanel.Effort("future-depth").toString());
      if (locale.equals("zh-CN")) {
        assertEquals("跟随模型默认: 中 (medium)", defaultLabel);
        assertEquals("模型默认（未公布）", new ChatGptSettingsPanel.Effort("").toString());
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

  @Test
  void defaultMetadataMustBeAnAdvertisedEffortNotANameBasedGuess() {
    JSONObject metadata =
        new JSONObject()
            .put(
                "supported_reasoning_levels",
                new JSONArray()
                    .put(new JSONObject().put("effort", "none"))
                    .put(new JSONObject().put("effort", "low"))
                    .put(new JSONObject().put("effort", "future-depth")));
    for (String value : List.of("none", "low", "future-depth")) {
      metadata.put("default_reasoning_level", value);
      assertEquals(
          value,
          new ChatGptCommentaryClient.Model("model", "Model", metadata).defaultReasoningEffort);
    }
    for (Object value :
        List.of(
            "medium", "LOW", " low", "<html>low", "", 1, true, new JSONObject(), JSONObject.NULL)) {
      metadata.put("default_reasoning_level", value);
      assertEquals(
          "", new ChatGptCommentaryClient.Model("model", "Model", metadata).defaultReasoningEffort);
    }
    metadata.remove("default_reasoning_level");
    assertEquals(
        "", new ChatGptCommentaryClient.Model("model", "Model", metadata).defaultReasoningEffort);
    metadata.remove("supported_reasoning_levels");
    metadata.put("default_reasoning_level", "low");
    assertEquals(
        "", new ChatGptCommentaryClient.Model("model", "Model", metadata).defaultReasoningEffort);
  }
}
