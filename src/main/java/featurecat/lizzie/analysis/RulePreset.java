package featurecat.lizzie.analysis;

import java.util.Optional;

/**
 * A rule set the rules caption at the top left of the main window switches to with one click:
 * KataGo rules together with the komi that belongs to them.
 *
 * <p>Both Chinese rule sets are KataGo's chinese rules and differ only in komi. The Chinese Weiqi
 * Association's 2027 trial rules (围棋竞赛规则（2027试行版）, published 2026-11-02, in force from 2027-01-01)
 * have Black give 3.5 stones, 7 points, so a game can end in a draw; the 2002 rules gave 3¾ stones,
 * 7.5 points. The 2027 rules break a draw, where a game must have a winner, either for White, which
 * counts like the 2002 komi, or for the player who passed first, which is KataGo's button rule;
 * both are left to the rules dialog.
 */
public enum RulePreset {
  CHINESE_2027("chinese", 7.0, "RulesQuickMenu.chinese2027"),
  CHINESE("chinese", 7.5, "RulesQuickMenu.chinese"),
  JAPANESE("japanese", 6.5, "RulesQuickMenu.japanese"),
  CHINESE_ANCIENT("stone-scoring", 7.5, "RulesQuickMenu.chnAncient"),
  TROMP_TAYLOR("tromp-taylor", 7.5, "RulesQuickMenu.trompTaylor"),
  GOE("goe", 7.5, "RulesQuickMenu.goe");

  /** The rules caption for Chinese rules with the 2027 komi. */
  public static final String CHINESE_2027_CAPTION = "LizzieFrame.currentRules.chinese2027";

  private final String kataGoName;
  private final double komi;
  private final String labelKey;

  RulePreset(String kataGoName, double komi, String labelKey) {
    this.kataGoName = kataGoName;
    this.komi = komi;
    this.labelKey = labelKey;
  }

  public KataGoRules rules() {
    return KataGoRules.parse(kataGoName).orElseThrow();
  }

  public double komi() {
    return komi;
  }

  /** Display-string key of the menu entry. */
  public String labelKey() {
    return labelKey;
  }

  /**
   * The preset that rules with this summary and komi are. The two Chinese presets share a summary
   * and are told apart by komi; any other preset is recognised whatever the komi.
   */
  public static Optional<RulePreset> of(KataGoRules.Summary summary, double komi) {
    for (RulePreset preset : values()) {
      KataGoRules.Summary own = preset.rules().summary();
      if (own == summary && (!sharedSummary(own) || preset.komi == komi)) {
        return Optional.of(preset);
      }
    }
    return Optional.empty();
  }

  /** The display-string key of the rules caption: Chinese rules with komi 7 are the 2027 rules. */
  public static String captionKey(KataGoRules.Summary summary, double komi) {
    return summary == KataGoRules.Summary.CHINESE && komi == CHINESE_2027.komi
        ? CHINESE_2027_CAPTION
        : summary.labelKey();
  }

  private static boolean sharedSummary(KataGoRules.Summary summary) {
    int presets = 0;
    for (RulePreset preset : values()) {
      if (preset.rules().summary() == summary) presets++;
    }
    return presets > 1;
  }
}
