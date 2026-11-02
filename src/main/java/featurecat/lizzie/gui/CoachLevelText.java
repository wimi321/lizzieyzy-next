package featurecat.lizzie.gui;

import featurecat.lizzie.training.CoachLevel;
import java.text.MessageFormat;
import java.util.function.BinaryOperator;

/**
 * How AI 陪练 names a level. Players know Fox / Tygem ranks, so the opponent goes by its Fox rank
 * ("野狐 3D"); the KataGo level and the real-world grade come second, and tell apart the few levels
 * that share a Fox rank.
 */
final class CoachLevelText {
  private CoachLevelText() {}

  /** "野狐 3D", the name of the opponent. */
  static String fox(BinaryOperator<String> text, CoachLevel level) {
    return MessageFormat.format(text.apply("HumanSlTraining.level.fox", "Fox {0}"), level.fox());
  }

  /** "3段", "强3段" or "10级", the level on KataGo's scale. */
  static String kataGo(BinaryOperator<String> text, CoachLevel level) {
    String plain =
        MessageFormat.format(
            level.dan()
                ? text.apply("HumanSlTraining.rank.danValue", "{0} dan")
                : text.apply("HumanSlTraining.rank.kyuValue", "{0} kyu"),
            level.rank());
    return level.strong()
        ? MessageFormat.format(text.apply("HumanSlTraining.rankTable.strong", "Strong {0}"), plain)
        : plain;
  }

  /** "业余3段" or "职业顶尖", the real-world grade. */
  static String grade(BinaryOperator<String> text, CoachLevel level) {
    switch (level.grade()) {
      case BEYOND_TOP_PRO:
        return text.apply("HumanSlTraining.rankTable.beyondTopPro", "Beyond top pro");
      case TOP_PRO:
        return text.apply("HumanSlTraining.rankTable.topPro", "Top pro");
      case PRO:
        return text.apply("HumanSlTraining.rankTable.pro", "Professional");
      case TOP_AMATEUR:
        return text.apply("HumanSlTraining.rankTable.topAmateur", "Top amateur");
      case AMATEUR_DAN:
        return MessageFormat.format(
            text.apply("HumanSlTraining.rankTable.amateurDan", "Amateur {0} dan"),
            level.gradeNumber());
      default:
        return MessageFormat.format(
            text.apply("HumanSlTraining.rankTable.amateurKyu", "Amateur {0} kyu"),
            level.gradeNumber());
    }
  }

  /** "KataGo 强3段 · 业余3段", what the Fox rank stands for. */
  static String detail(BinaryOperator<String> text, CoachLevel level) {
    return MessageFormat.format(
        text.apply("HumanSlTraining.level.detail", "KataGo {0} · {1}"),
        kataGo(text, level),
        grade(text, level));
  }
}
