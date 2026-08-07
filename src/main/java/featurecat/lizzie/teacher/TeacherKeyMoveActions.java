package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.Severity;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

/** 对齐 GoAgent 的 TeacherKeyMoveActions：关键手列表 + 跳转/重析按钮。 展示整盘或区间里被判定为问题手/失误的关键手，可一键跳转或重新分析。 */
public class TeacherKeyMoveActions extends JPanel {
  /** 多语言：读主程序当前语言 bundle（与主程序对齐），缺 key 时回退默认文本。 */
  private static String t(String key, String fallback) {
    try {
      if (featurecat.lizzie.Lizzie.resourceBundle != null && featurecat.lizzie.Lizzie.resourceBundle.containsKey(key)) {
        return featurecat.lizzie.Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception ignored) {
    }
    return fallback;
  }

  public interface Handler {
    void onJumpToMove(int moveNumber);

    void onAnalyzeMove(int moveNumber);
  }

  public static class KeyMoveItem {
    public final int moveNumber;
    public final String title;
    public final String summary;
    public final String severity;

    public KeyMoveItem(int moveNumber, String title, String summary, String severity) {
      this.moveNumber = moveNumber;
      this.title = title;
      this.summary = summary;
      this.severity = severity;
    }
  }

  public TeacherKeyMoveActions(List<KeyMoveItem> moves, Handler handler) {
    setLayout(new BorderLayout(4, 4));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "关键手", TitledBorder.LEFT, TitledBorder.TOP));

    JPanel list = new JPanel();
    list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
    for (KeyMoveItem m : moves.subList(0, Math.min(6, moves.size()))) {
      JPanel row = new JPanel(new BorderLayout(6, 2));
      JPanel info = new JPanel(new java.awt.GridLayout(0, 1));
      JLabel title = new JLabel(m.title != null ? m.title : java.text.MessageFormat.format(t("TeacherKeyMoveActions.moveTitle", "第 {0} 手"), m.moveNumber));
      title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      info.add(title);
      if (m.summary != null && !m.summary.isEmpty()) {
        JLabel sum = new JLabel(m.summary);
        sum.setForeground(java.awt.Color.GRAY);
        info.add(sum);
      }
      // severity 颜色（对齐 GoAgent：问题手红/缓手黄/好手绿）
      java.awt.Color sevColor = java.awt.Color.GRAY;
      if ("BLUNDER".equals(m.severity) || "MISTAKE".equals(m.severity))
        sevColor = new java.awt.Color(0xCC, 0x33, 0x33);
      else if ("INACCURACY".equals(m.severity)) sevColor = new java.awt.Color(0xCC, 0x88, 0x00);
      else if ("GOOD".equals(m.severity)) sevColor = new java.awt.Color(0x22, 0x99, 0x44);
      title.setForeground(sevColor);
      row.add(info, BorderLayout.CENTER);

      JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
      JButton jump = new JButton(t("TeacherKeyMoveActions.jump", "跳转"));
      jump.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      jump.addActionListener(e -> handler.onJumpToMove(m.moveNumber));
      JButton reAnalyze = new JButton(t("TeacherKeyMoveActions.reanalyze", "重析"));
      reAnalyze.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      reAnalyze.addActionListener(e -> handler.onAnalyzeMove(m.moveNumber));
      buttons.add(jump);
      buttons.add(reAnalyze);
      row.add(buttons, BorderLayout.EAST);
      list.add(row);
    }
    add(list, BorderLayout.CENTER);
  }

  /** 从整盘分类结果里挑出值得讲的关键手（severity 非 good 且 shouldTeach） */
  public static List<KeyMoveItem> fromClassifications(List<MoveClassification> all) {
    List<KeyMoveItem> out = new ArrayList<>();
    for (MoveClassification mc : all) {
      if (mc.shouldTeach && mc.severity != Severity.GOOD && mc.severity != Severity.UNCLEAR) {
        int mn = mc.moveNumber > 0 ? mc.moveNumber : 0;
        out.add(
            new KeyMoveItem(
                mn,
                "第 " + mn + " 手",
                mc.severity + "/" + mc.confidence,
                mc.severity.name()));
      }
    }
    return out;
  }
}
