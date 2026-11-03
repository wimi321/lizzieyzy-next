package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulePresetTest {
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();

  @TempDir Path workDirectory;

  @Test
  void the2027RuleSetsAreKataGosChineseRulesWithTheirKomiAndDrawRule() {
    assertTrue(RulePreset.CHINESE_2027.rules().semanticallyEquals(CHINESE));
    assertEquals(7.0, RulePreset.CHINESE_2027.komi());
    assertTrue(RulePreset.CHINESE_2027_DRAW_TO_WHITE.rules().semanticallyEquals(CHINESE));
    assertEquals(7.5, RulePreset.CHINESE_2027_DRAW_TO_WHITE.komi());
    assertEquals(7.5, RulePreset.CHINESE.komi());

    KataGoRules button = RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules();
    assertTrue(button.bool(KataGoRules.HAS_BUTTON));
    assertTrue(button.hasRequiredFields());
    assertEquals(
        CHINESE.overlayEditor("AREA", "SIMPLE", false, "NONE", "N", true, true).fields(),
        button.fields());
    assertEquals(7.0, RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.komi());
  }

  @Test
  void everyPresetButThe2027OneUsesKataGosUsualKomiForItsRules() {
    for (RulePreset preset : RulePreset.values()) {
      if (preset == RulePreset.CHINESE_2027) continue;
      assertEquals(
          preset.rules().defaultKomi().getAsDouble(), preset.komi(), preset.name() + " komi");
    }
    assertEquals(6.5, RulePreset.JAPANESE.komi());
  }

  @Test
  void chineseRulesAreToldApartByKomiAndADrawToWhiteOnlyWhenItWasChosen() {
    Optional<RulePreset> none = Optional.empty();
    assertEquals(Optional.of(RulePreset.CHINESE_2027), RulePreset.of(CHINESE, 7.0, none));
    assertEquals(Optional.of(RulePreset.CHINESE), RulePreset.of(CHINESE, 7.5, none));
    assertEquals(
        Optional.of(RulePreset.CHINESE_2027_DRAW_TO_WHITE),
        RulePreset.of(CHINESE, 7.5, Optional.of(RulePreset.CHINESE_2027_DRAW_TO_WHITE)));
    assertEquals(
        Optional.of(RulePreset.CHINESE_2027),
        RulePreset.of(CHINESE, 7.0, Optional.of(RulePreset.CHINESE_2027_DRAW_TO_WHITE)));
    assertEquals(Optional.empty(), RulePreset.of(CHINESE, 0.5, none));
  }

  @Test
  void rulesOnlyOnePresetHasAreRecognisedWhateverTheKomi() {
    Optional<RulePreset> none = Optional.empty();
    KataGoRules button = RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules();
    assertEquals(
        Optional.of(RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS), RulePreset.of(button, 7.0, none));
    assertEquals(
        Optional.of(RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS), RulePreset.of(button, 0.5, none));
    KataGoRules japanese = KataGoRules.parse("japanese").orElseThrow();
    assertEquals(Optional.of(RulePreset.JAPANESE), RulePreset.of(japanese, 0.5, none));
    assertEquals(
        Optional.empty(), RulePreset.of(KataGoRules.parse("aga").orElseThrow(), 7.5, none));
    assertEquals(Optional.empty(), RulePreset.of(null, 7.0, none));
  }

  @Test
  void theCaptionNamesThe2027RuleSetsAndOtherwiseTheRulesFamily() {
    Config config = config();
    String chinese = CHINESE.toResponseLine();
    String button = RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules().toResponseLine();

    assertEquals(
        "LizzieFrame.currentRules.chinese2027", RulePreset.captionKey(chinese, 7.0, config));
    assertEquals(
        KataGoRules.Summary.CHINESE.labelKey(), RulePreset.captionKey(chinese, 7.5, config));
    assertEquals(
        KataGoRules.Summary.CHINESE.labelKey(), RulePreset.captionKey(chinese, 0.5, config));
    assertEquals(
        "LizzieFrame.currentRules.chinese2027DrawToFirstPass",
        RulePreset.captionKey(button, 7.0, config));
    assertEquals(
        KataGoRules.Summary.JAPANESE.labelKey(), RulePreset.captionKey("= japanese", 7.0, config));
    assertNull(RulePreset.captionKey("", 7.5, config));

    choose(config, RulePreset.CHINESE_2027_DRAW_TO_WHITE);
    assertEquals(
        "LizzieFrame.currentRules.chinese2027DrawToWhite",
        RulePreset.captionKey(chinese, 7.5, config));
  }

  @Test
  void theChosenRuleSetCountsOnlyWhileKataGoStartsWithItsRules() {
    Config config = config();
    assertEquals(Optional.empty(), RulePreset.chosen(config));
    assertEquals(7.5f, RulePreset.newGameKomi(config, 7.5f));

    choose(config, RulePreset.CHINESE_2027);
    assertEquals(Optional.of(RulePreset.CHINESE_2027), RulePreset.chosen(config));
    assertEquals(7.0f, RulePreset.newGameKomi(config, 7.5f));

    config.kataRules = KataGoRules.parse("japanese").orElseThrow().toGtpArgument();
    assertEquals(Optional.empty(), RulePreset.chosen(config), "startup rules changed since");
    assertEquals(6.5f, RulePreset.newGameKomi(config, 6.5f));

    choose(config, RulePreset.CHINESE_2027);
    config.autoLoadKataRules = false;
    assertEquals(Optional.empty(), RulePreset.chosen(config), "KataGo starts with its own rules");

    config.autoLoadKataRules = true;
    config.uiConfig.put(RulePreset.CHOSEN_KEY, "NO_SUCH_RULES");
    assertEquals(Optional.empty(), RulePreset.chosen(config));
  }

  @Test
  void anEvenGameTakesTheKomiOfTheRulesKataGoPlaysNow() {
    Config config = config();
    config.currentKataGoRules = CHINESE.toResponseLine();
    assertEquals(7.0, RulePreset.evenGameKomi(config, 7.0, false), "2027 rules");
    assertEquals(7.5, RulePreset.evenGameKomi(config, 7.5, false), "2002 rules");
    assertEquals(6.0, RulePreset.evenGameKomi(config, 6.0, false), "no rule set: the game's komi");
    assertEquals(7.5, RulePreset.evenGameKomi(config, 0.5, true), "after a handicap game");

    choose(config, RulePreset.CHINESE_2027);
    assertEquals(7.0, RulePreset.evenGameKomi(config, 0.5, true), "the komi new games start with");
    config.currentKataGoRules = RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules().toResponseLine();
    assertEquals(7.0, RulePreset.evenGameKomi(config, 7.5, false), "the button goes with komi 7");
    config.currentKataGoRules = KataGoRules.parse("japanese").orElseThrow().toResponseLine();
    assertEquals(6.5, RulePreset.evenGameKomi(config, 7.5, false));
    config.currentKataGoRules = "";
    assertEquals(7.5, RulePreset.evenGameKomi(config, 7.5, false), "before KataGo reports rules");
  }

  @Test
  void aConfigWithoutSettingsHasNoChosenRuleSet() {
    Config bare = ConfigTestHelper.createForTests(workDirectory);
    bare.uiConfig = null;
    assertFalse(RulePreset.chosen(bare).isPresent());
    assertEquals(7.5f, RulePreset.newGameKomi(bare, 7.5f));
    assertEquals(7.5f, RulePreset.newGameKomi(null, 7.5f));
  }

  @Test
  void everyPresetHasAMenuEntryAndACaptionInEveryLanguage() {
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
        assertFalse(strings.getString(preset.labelKey()).isBlank(), preset + " in " + locale);
        assertFalse(strings.getString(preset.captionKey()).isBlank(), preset + " in " + locale);
      }
      assertTrue(strings.getString(RulePreset.CHINESE_2027.captionKey()).contains("2027"));
      assertTrue(
          strings.getString(RulePreset.CHINESE_2027_DRAW_TO_WHITE.labelKey()).contains("7.5"));
    }
  }

  private Config config() {
    Config config = ConfigTestHelper.createForTests(workDirectory);
    config.uiConfig = new JSONObject();
    config.autoLoadKataRules = false;
    config.kataRules = "";
    return config;
  }

  private static void choose(Config config, RulePreset preset) {
    config.uiConfig.put(RulePreset.CHOSEN_KEY, preset.name());
    config.autoLoadKataRules = true;
    config.kataRules = preset.rules().toGtpArgument();
  }
}
