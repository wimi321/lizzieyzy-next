package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

/**
 * 对齐 GoAgent 的 TeacherArtifactCard：把单手讲解组织成结构化事实表格（FactCell）。 展示：实战点 / AI 首选 / 胜率损失 / 目差损失 /
 * 判定(severity/confidence)。
 */
public class TeacherArtifactCard extends JPanel {
  public TeacherArtifactCard(
      int moveNumber,
      String actualMove,
      KataGoCandidate best,
      MoveClassification mc,
      double actualWinrate,
      double actualScoreLead) {
    setLayout(new BorderLayout(4, 4));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "第 " + moveNumber + " 手讲解",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 13)));

    JPanel grid = new JPanel(new GridLayout(0, 1, 2, 2));
    List<FactCell> facts =
        buildFacts(moveNumber, actualMove, best, mc, actualWinrate, actualScoreLead);
    for (FactCell f : facts) {
      JPanel row = new JPanel(new BorderLayout(6, 2));
      JLabel label = new JLabel(f.label);
      label.setForeground(Color.GRAY);
      JLabel value = new JLabel(f.value);
      if (f.toneLoss) value.setForeground(new Color(0xCC, 0x33, 0x33));
      else value.setForeground(new Color(0x22, 0x22, 0x22));
      value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      row.add(label, BorderLayout.WEST);
      row.add(value, BorderLayout.EAST);
      grid.add(row);
    }
    add(grid, BorderLayout.CENTER);
  }

  private static List<FactCell> buildFacts(
      int moveNumber,
      String actualMove,
      KataGoCandidate best,
      MoveClassification mc,
      double actualWinrate,
      double actualScoreLead) {
    List<FactCell> facts = new ArrayList<>();
    facts.add(new FactCell("实战点", actualMove == null ? "未知" : actualMove));
    if (best != null) {
      facts.add(new FactCell("AI 首选", best.move));
      double wrLoss = (best.winrate - actualWinrate) * 100;
      double scLoss = best.scoreLead - actualScoreLead;
      facts.add(new FactCell("胜率损失", String.format("%.1f%%", wrLoss), true));
      facts.add(new FactCell("目差损失", String.format("%.1f 目", scLoss), true));
    }
    if (mc != null) {
      facts.add(new FactCell("判定", mc.severity + " / " + mc.confidence));
      facts.add(new FactCell("建议", mc.shouldDeepen ? "加深后讲解" : (mc.shouldTeach ? "值得讲解" : "可略过")));
    }
    return facts;
  }

  private static class FactCell {
    final String label;
    final String value;
    final boolean toneLoss;

    FactCell(String label, String value) {
      this(label, value, false);
    }

    FactCell(String label, String value, boolean toneLoss) {
      this.label = label;
      this.value = value;
      this.toneLoss = toneLoss;
    }
  }
}
