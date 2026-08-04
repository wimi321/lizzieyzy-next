package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.BoardData;
import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 GoAgent 的 evidenceChipsFromAnalysis：把一手 KataGo 分析派生为证据链 chips。 数据源：lizzieyzy 的
 * BoardData（winrate / scoreMean=目差 / playouts / bestMoves）。
 */
public final class EvidenceChips {

  private EvidenceChips() {}

  /**
   * @param data 当前手的 BoardData 分析
   * @param actualMove 实战落子坐标（如 "Q4"），无则 null
   * @param bestMove AI 首选坐标（从 bestMovesToString 解析第一个）
   * @param bestWinrate AI 首选胜率
   * @param bestScoreLead AI 首选目差（scoreMean）
   * @param moveNumber 手数
   */
  public static List<TeacherEvidenceChip> fromAnalysis(
      BoardData data,
      String actualMove,
      String bestMove,
      double bestWinrate,
      double bestScoreLead,
      int moveNumber) {
    List<TeacherEvidenceChip> chips = new ArrayList<>();

    chips.add(
        new TeacherEvidenceChip(
            "move-" + moveNumber,
            TeacherEvidenceChip.Kind.MOVE,
            "第 " + moveNumber + " 手",
            "当前局面分析",
            moveNumber,
            null));

    if (actualMove != null && !actualMove.isEmpty()) {
      chips.add(
          new TeacherEvidenceChip(
              "actual-" + moveNumber + "-" + actualMove,
              TeacherEvidenceChip.Kind.COORDINATE,
              "实战 " + actualMove,
              "实战点 " + actualMove,
              moveNumber,
              actualMove));
    }

    if (bestMove != null && !bestMove.isEmpty()) {
      chips.add(
          new TeacherEvidenceChip(
              "best-" + moveNumber + "-" + bestMove,
              TeacherEvidenceChip.Kind.CANDIDATE,
              "AI 首选 " + bestMove,
              String.format(
                  "胜率 %.1f%%，目差 %.1f，搜索 %d", bestWinrate * 100, bestScoreLead, data.getPlayouts()),
              moveNumber,
              bestMove));
    }

    // 损失：实战点 vs AI 首选的胜率差 / 目差
    if (actualMove != null
        && !actualMove.isEmpty()
        && bestMove != null
        && !bestMove.isEmpty()
        && !actualMove.equals(bestMove)) {
      double loss = (bestWinrate - data.winrate) * 100;
      double scoreLoss = bestScoreLead - data.scoreMean;
      chips.add(
          new TeacherEvidenceChip(
              "loss-" + moveNumber,
              TeacherEvidenceChip.Kind.LOSS,
              String.format("损失 %.1f%% / %.1f目", loss, scoreLoss),
              String.format("胜率损失 %.1f%%，目差损失 %.1f目。", loss, scoreLoss),
              moveNumber,
              null));
    }

    return chips;
  }

  /** 从 BoardData.bestMovesToString() 解析第一个候选坐标（格式 "Q4 (52.3%, ...)" 之类） */
  public static String parseFirstBestMove(String bestMovesString) {
    if (bestMovesString == null) return null;
    // lizzie bestMovesToString 形如 "Q4 (52.30%, 0.50, 1200)" 或 "Q4 Visits:1200 ..."，取首个坐标词
    String[] parts = bestMovesString.trim().split("\\s+");
    for (String p : parts) {
      if (p.matches("^[A-Ta-t][0-9]{1,2}$")) return p.toUpperCase();
    }
    return null;
  }
}
