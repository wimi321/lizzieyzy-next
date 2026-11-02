package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class RulePresetTest {
  @Test
  void theTwoChineseRuleSetsAreKataGosChineseRulesWithTheirOwnKomi() {
    assertTrue(RulePreset.CHINESE_2027.rules().semanticallyEquals(RulePreset.CHINESE.rules()));
    assertEquals(KataGoRules.Summary.CHINESE, RulePreset.CHINESE_2027.rules().summary());
    assertEquals(7.0, RulePreset.CHINESE_2027.komi());
    assertEquals(7.5, RulePreset.CHINESE.komi());
  }

  @Test
  void everyOtherPresetUsesKataGosUsualKomiForItsRules() {
    for (RulePreset preset : RulePreset.values()) {
      if (preset == RulePreset.CHINESE_2027) continue;
      assertEquals(
          preset.rules().defaultKomi().getAsDouble(), preset.komi(), preset.name() + " komi");
    }
    assertEquals(6.5, RulePreset.JAPANESE.komi());
  }

  @Test
  void theCurrentPresetIsToldApartByKomiOnlyForTheChineseRules() {
    assertEquals(
        Optional.of(RulePreset.CHINESE_2027), RulePreset.of(KataGoRules.Summary.CHINESE, 7.0));
    assertEquals(Optional.of(RulePreset.CHINESE), RulePreset.of(KataGoRules.Summary.CHINESE, 7.5));
    assertEquals(Optional.empty(), RulePreset.of(KataGoRules.Summary.CHINESE, 0.5));
    assertEquals(
        Optional.of(RulePreset.JAPANESE), RulePreset.of(KataGoRules.Summary.JAPANESE, 0.5));
    assertEquals(Optional.empty(), RulePreset.of(KataGoRules.Summary.OTHER, 7.5));
    assertEquals(Optional.empty(), RulePreset.of(null, 7.0));
  }

  @Test
  void chineseRulesWithKomiSevenAreCaptionedAsThe2027Rules() {
    assertEquals(
        RulePreset.CHINESE_2027_CAPTION, RulePreset.captionKey(KataGoRules.Summary.CHINESE, 7.0));
    assertEquals(
        KataGoRules.Summary.CHINESE.labelKey(),
        RulePreset.captionKey(KataGoRules.Summary.CHINESE, 7.5));
    assertEquals(
        KataGoRules.Summary.JAPANESE.labelKey(),
        RulePreset.captionKey(KataGoRules.Summary.JAPANESE, 7.0));
  }

  @Test
  void everyPresetAndTheNewCaptionHaveTextInEveryLanguage() {
    for (Locale locale :
        new Locale[] {
          Locale.US,
          Locale.SIMPLIFIED_CHINESE,
          Locale.TRADITIONAL_CHINESE,
          Locale.forLanguageTag("zh-HK"),
          Locale.JAPAN,
          Locale.KOREAN,
          Locale.forLanguageTag("th-TH")
        }) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (RulePreset preset : RulePreset.values()) {
        assertTrue(!strings.getString(preset.labelKey()).isBlank(), preset + " in " + locale);
      }
      assertTrue(strings.getString(RulePreset.CHINESE_2027_CAPTION).contains("2027"));
    }
  }
}
