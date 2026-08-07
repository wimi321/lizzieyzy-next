package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.knowledge.MotifRecognizer.RecognizedTeachingMotif;
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
 * 对齐 GoAgent 的 TeacherArtifactCard：单手讲解结构化卡片。 展示：FactCell(实战点/AI首选/胜率损失/目差损失/判定) + 知识匹配 +
 * 关键变化(variations) + 练习建议(trainingItems)。 variations/trainingItems 由 LLM 结构化返回后填充。
 */
public class TeacherArtifactCard extends JPanel {
  public static class Variation {
    public String label;
    public String purpose;
    public String pv;
    public String result;

    public Variation(String label, String purpose, String pv, String result) {
      this.label = label;
      this.purpose = purpose;
      this.pv = pv;
      this.result = result;
    }
  }

  public static class TrainingItem {
    public String title;
    public String kind;
    public String objective;

    public TrainingItem(String title, String kind, String objective) {
      this.title = title;
      this.kind = kind;
      this.objective = objective;
    }
  }

  public TeacherArtifactCard(
      int moveNumber,
      String actualMove,
      KataGoCandidate best,
      MoveClassification mc,
      double actualWinrate,
      double actualScoreLead,
      List<RecognizedTeachingMotif> knowledge,
      List<Variation> variations,
      List<TrainingItem> trainingItems) {
    setLayout(new BorderLayout(4, 4));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.titleMove", "第 {0} 手讲解"), moveNumber),
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 13)));

    JPanel grid = new JPanel(new GridLayout(0, 1, 2, 2));
    for (FactCell f :
        buildFacts(moveNumber, actualMove, best, mc, actualWinrate, actualScoreLead)) {
      JPanel row = new JPanel(new BorderLayout(6, 2));
      JLabel label = new JLabel(f.label);
      label.setForeground(Color.GRAY);
      JLabel value = new JLabel("<html>" + escapeHtml(f.value) + "</html>");
      if (f.toneLoss) value.setForeground(new Color(0xCC, 0x33, 0x33));
      else value.setForeground(new Color(0x22, 0x22, 0x22));
      value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      row.add(label, BorderLayout.WEST);
      row.add(value, BorderLayout.CENTER);
      grid.add(row);
    }

    if (knowledge != null && !knowledge.isEmpty()) {
      grid.add(sectionTitle(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.knowledge", "知识匹配")));
      for (RecognizedTeachingMotif km : knowledge) {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        JLabel label = new JLabel(motifTypeZh(km.motifType));
        label.setForeground(Color.GRAY);
        JLabel value = new JLabel(km.title + " (" + km.confidence.name() + ")");
        value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        grid.add(row);
      }
    }

    if (variations != null && !variations.isEmpty()) {
      grid.add(sectionTitle(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.keyVariation", "关键变化 / 正确思路")));
      for (Variation v : variations) {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        JLabel label = new JLabel(v.label);
        label.setForeground(Color.GRAY);
        JLabel value =
            new JLabel(
                (v.purpose != null ? v.purpose + " " : "")
                    + (v.result != null ? "→ " + v.result : ""));
        value.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        grid.add(row);
      }
    }

    if (trainingItems != null && !trainingItems.isEmpty()) {
      grid.add(sectionTitle(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.practice", "练习建议")));
      for (TrainingItem t : trainingItems) {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        JLabel label = new JLabel(t.kind);
        label.setForeground(Color.GRAY);
        JLabel value = new JLabel(t.title + "：" + t.objective);
        value.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        grid.add(row);
      }
    }

    add(grid, BorderLayout.CENTER);
  }

  private static JLabel sectionTitle(String t) {
    JLabel l = new JLabel(t);
    l.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
    l.setForeground(Color.GRAY);
    return l;
  }

  private static List<FactCell> buildFacts(
      int moveNumber,
      String actualMove,
      KataGoCandidate best,
      MoveClassification mc,
      double actualWinrate,
      double actualScoreLead) {
    List<FactCell> facts = new ArrayList<>();
    facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.actualPoint", "实战点"), actualMove == null ? featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.unknown", "未知") : actualMove));
    if (best != null) {
      facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.aiBest", "AI 首选"), best.move));
      double wrLoss = best.winrate - actualWinrate;  // 都是百分比(0-100)，差值就是百分点，不能再乘100
      double scLoss = best.scoreLead - actualScoreLead;
      facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.winrateLoss", "胜率损失"), String.format("%.1f%%", wrLoss), true));
      facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.scoreLoss", "目差损失"), String.format("%.1f 目", scLoss), true));
    }
    if (mc != null) {
      facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.judgment", "判定"), mc.severity + " / " + mc.confidence));
      facts.add(new FactCell(featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.advice", "建议"), mc.shouldDeepen ? featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.deepenAdvice", "加深后讲解") : (mc.shouldTeach ? featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.worthTeaching", "值得讲解") : featurecat.lizzie.teacher.TeacherI18n.t("TeacherArtifactCard.skip", "可略过"))));
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
  /** motifType 英文枚举 → 中文显示 */
  private static String motifTypeZh(String t) {
    if (t == null) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.type", "知识");
    String l = t.toLowerCase();
    if (l.contains("joseki")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.joseki", "定式");
    if (l.contains("life") || l.contains("death")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.lifeDeath", "死活");
    if (l.contains("tesuji")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.tesuji", "手筋");
    if (l.contains("shape")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.shape", "棋形");
    if (l.contains("opening")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.opening", "布局");
    if (l.contains("endgame")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.endgame", "官子");
    if (l.contains("fuseki")) return featurecat.lizzie.teacher.TeacherI18n.t("Knowledge.opening", "布局");
    return t;
  }

  /** HTML 转义（避免 < > & 破坏 JLabel HTML 渲染） */
  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
