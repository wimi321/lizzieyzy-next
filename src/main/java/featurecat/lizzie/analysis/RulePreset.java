package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A rule set the rules caption at the top left of the main window switches to with one click:
 * KataGo rules together with the komi that belongs to them.
 *
 * <p>The Chinese rule sets are KataGo's chinese rules and differ in komi and in how a draw is
 * broken. The Chinese Weiqi Association's 2027 trial rules (围棋竞赛规则（2027试行版）, published 2026-11-02,
 * in force from 2027-01-01) have Black give 3.5 stones, 7 points, so a game can end in a draw; the
 * 2002 rules gave 3¾ stones, 7.5 points. Where a game must have a winner, the 2027 rules break a
 * draw either for White, which counts exactly like komi 7.5, or for the player who passed first,
 * which is KataGo's button rule with komi 7.
 *
 * <p>The rule set picked last becomes the rules new games start with: KataGo's startup rules and
 * the komi of new games, for as long as the startup rules stay that rule set's rules.
 */
public enum RulePreset {
  CHINESE_2027(
      "chinese", 7.0, "RulesQuickMenu.chinese2027", "LizzieFrame.currentRules.chinese2027", false),
  CHINESE_2027_DRAW_TO_WHITE(
      "chinese",
      7.5,
      "RulesQuickMenu.chinese2027DrawToWhite",
      "LizzieFrame.currentRules.chinese2027DrawToWhite",
      true),
  CHINESE_2027_DRAW_TO_FIRST_PASS(
      // KataGo's chinese rules with the button, which the player who passes first takes.
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":true,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}",
      7.0,
      "RulesQuickMenu.chinese2027DrawToFirstPass",
      "LizzieFrame.currentRules.chinese2027DrawToFirstPass",
      true),
  CHINESE("chinese", 7.5, "RulesQuickMenu.chinese", null, false),
  JAPANESE("japanese", 6.5, "RulesQuickMenu.japanese", null, false),
  CHINESE_ANCIENT("stone-scoring", 7.5, "RulesQuickMenu.chnAncient", null, false),
  TROMP_TAYLOR("tromp-taylor", 7.5, "RulesQuickMenu.trompTaylor", null, false),
  GOE("goe", 7.5, "RulesQuickMenu.goe", null, false);

  /** uiConfig key of the rule set picked last from the rules caption. */
  public static final String CHOSEN_KEY = "rule-preset";

  private final KataGoRules rules;
  private final KataGoRules.Summary summary;
  private final double komi;
  private final String labelKey;
  private final String captionKey;
  private final boolean drawRule;

  RulePreset(
      String kataGoRules, double komi, String labelKey, String captionKey, boolean drawRule) {
    this.rules = KataGoRules.parse(kataGoRules).orElseThrow();
    this.summary = rules.summary();
    this.komi = komi;
    this.labelKey = labelKey;
    this.captionKey = captionKey == null ? summary.labelKey() : captionKey;
    this.drawRule = drawRule;
  }

  public KataGoRules rules() {
    return rules;
  }

  public double komi() {
    return komi;
  }

  /** Display-string key of the menu entry. */
  public String labelKey() {
    return labelKey;
  }

  /** Display-string key of the rules caption. */
  public String captionKey() {
    return captionKey;
  }

  /**
   * The preset that these rules and komi are. Presets with the same rules are told apart by komi; a
   * preset whose rules no other preset has is recognised whatever the komi. A draw rule that counts
   * like another preset is recognised only when it is {@code chosen}, the one picked last.
   */
  public static Optional<RulePreset> of(
      KataGoRules rules, double komi, Optional<RulePreset> chosen) {
    if (rules == null) {
      return Optional.empty();
    }
    KataGoRules.Summary observedSummary = rules.summary();
    List<RulePreset> matches = new ArrayList<>();
    for (RulePreset preset : values()) {
      if (preset.hasRules(rules, observedSummary)
          && (!preset.sharesRules() || preset.komi == komi)) {
        matches.add(preset);
      }
    }
    if (chosen.isPresent() && matches.contains(chosen.get())) {
      return chosen;
    }
    for (RulePreset preset : matches) {
      if (!preset.drawRule || matches.size() == 1) {
        return Optional.of(preset);
      }
    }
    return Optional.empty();
  }

  /**
   * The display-string key of the rules caption for the rules line KataGo reported and the komi, or
   * null when the line holds no rules.
   */
  public static String captionKey(String rulesLine, double komi, Config config) {
    KataGoRules rules = KataGoRules.parse(rulesLine).orElse(null);
    if (rules == null) {
      return null;
    }
    return of(rules, komi, chosen(config))
        .map(RulePreset::captionKey)
        .orElse(rules.summary().labelKey());
  }

  /** The rule set picked last, while KataGo still starts with its rules. */
  public static Optional<RulePreset> chosen(Config config) {
    if (config == null || config.uiConfig == null || !config.autoLoadKataRules) {
      return Optional.empty();
    }
    String name = config.uiConfig.optString(CHOSEN_KEY, "");
    KataGoRules startup = KataGoRules.parse(config.kataRules).orElse(null);
    for (RulePreset preset : values()) {
      if (preset.name().equals(name)) {
        return preset.rules.semanticallyEquals(startup) ? Optional.of(preset) : Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** The komi new games start with: the chosen rule set's, else {@code otherwise}. */
  public static float newGameKomi(Config config, float otherwise) {
    return chosen(config).map(preset -> (float) preset.komi).orElse(otherwise);
  }

  private boolean hasRules(KataGoRules observed, KataGoRules.Summary observedSummary) {
    // A named KataGo rule set is recognised by its summary, which allows its close variants.
    return summary == KataGoRules.Summary.OTHER
        ? rules.semanticallyEquals(observed)
        : summary == observedSummary;
  }

  private boolean sharesRules() {
    for (RulePreset other : values()) {
      if (other != this && other.hasRules(rules, summary)) {
        return true;
      }
    }
    return false;
  }
}
