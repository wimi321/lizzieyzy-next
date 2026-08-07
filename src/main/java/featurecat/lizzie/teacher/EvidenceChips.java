package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 GoAgent 的 evidenceChipsFromAnalysis：把一手 KataGo 分析派生为证据链 chips。 数据来源：lizzieyzy 的
 * BoardData（winrate / scoreMean=目差 / playouts / bestMoves）。 增强：接入 AnalysisBrain
 * 的失误分类(classifier)与变化图可信度(pvConfidence)。
 */
public final class EvidenceChips {

  private EvidenceChips() {}

  public static List<TeacherEvidenceChip> fromAnalysis(
      int moveNumber,
      String actualMove,
      Double actualWinrate,
      Double actualScoreLead,
      Integer actualVisits,
      KataGoCandidate best,
      MoveClassification classification,
      PvReport pv) {

    List<TeacherEvidenceChip> chips = new ArrayList<>();

    chips.add(
        new TeacherEvidenceChip(
            "move-" + moveNumber,
            TeacherEvidenceChip.Kind.MOVE,
            java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.moveTitle", "第 {0} 手"), moveNumber),
            classification != null ? classification.reason : featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.currentAnalysis", "当前局面分析"),
            moveNumber,
            null));

    if (actualMove != null && !actualMove.isEmpty()) {
      chips.add(
          new TeacherEvidenceChip(
              "actual-" + moveNumber + "-" + actualMove,
              TeacherEvidenceChip.Kind.COORDINATE,
              java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.actualMove", "实战 {0}"), actualMove),
              java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.actualPoint", "实战点 {0}"), actualMove),
              moveNumber,
              actualMove));
    }

    if (best != null) {
      chips.add(
          new TeacherEvidenceChip(
              "best-" + moveNumber + "-" + best.move,
              TeacherEvidenceChip.Kind.CANDIDATE,
              java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.aiBest", "AI 首选 {0}"), best.move),
              java.text.MessageFormat.format(
                  featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.winrateDetail", "胜率 {0}%，目差 {1}，搜索 {2}"),
                  best.winrateOrZero(), best.scoreLeadOrZero(), best.visits),
              moveNumber,
              best.move));
    }

    if (actualMove != null && best != null && !actualMove.equals(best.move)) {
      // winrate 已是百分比（0-100），差值就是百分点，不能再乘 100
      double loss = best.winrateOrZero() - (actualWinrate == null ? 0 : actualWinrate);
      double scoreLoss = best.scoreLeadOrZero() - (actualScoreLead == null ? 0 : actualScoreLead);
      chips.add(
          new TeacherEvidenceChip(
              "loss-" + moveNumber,
              TeacherEvidenceChip.Kind.LOSS,
              java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.loss", "损失 {0}% / {1}目"), loss, scoreLoss),
              java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.lossDetail", "胜率损失 {0}%，目差损失 {1}目。"), loss, scoreLoss),
              moveNumber,
              null));
    }

    if (pv != null) {
      chips.add(
          new TeacherEvidenceChip(
              "pv-" + moveNumber,
              TeacherEvidenceChip.Kind.PV,
              "PV " + pv.overall,
              pv.summary,
              moveNumber,
              null));
    }

    if (classification != null) {
      chips.add(
          new TeacherEvidenceChip(
              "confidence-" + moveNumber,
              TeacherEvidenceChip.Kind.CONFIDENCE,
              classification.severity + "/" + classification.confidence,
              classification.reason,
              moveNumber,
              null));
      if (classification.shouldTeach) {
        chips.add(
            new TeacherEvidenceChip(
                "teach-" + moveNumber,
                TeacherEvidenceChip.Kind.CONFIDENCE,
                classification.shouldDeepen ? featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.teachDeepen", "建议加深后讲解") : featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.teachWorth", "值得讲解"),
                classification.shouldDeepen ? featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.teachDeepenDetail", "搜索不足，建议加深分析。") : featurecat.lizzie.teacher.TeacherI18n.t("EvidenceChips.teachWorthDetail", "证据充分，可讲解。"),
                moveNumber,
                null));
      }
    }

    return chips;
  }

  /** 从 BoardData.bestMovesToString() 解析第一个候选坐标（格式 "Q4 (52.3%, ...)" 之类） */
  public static String parseFirstBestMove(String bestMovesString) {
    if (bestMovesString == null) return null;
    String[] parts = bestMovesString.trim().split("\\s+");
    for (String p : parts) {
      if (p.matches("^[A-Ta-t][0-9]{1,2}$")) return p.toUpperCase();
    }
    return null;
  }
}
