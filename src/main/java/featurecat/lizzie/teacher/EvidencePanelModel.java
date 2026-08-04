package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对齐 GoAgent 的 evidencePanelModel.ts：把证据链 chips 分组成带标题的分区， 每个分区含 title + chips +
 * summary，并按优先级排序、过滤空分区。 同时提供 evidencePanelCopyText 生成可复制文本。
 */
public final class EvidencePanelModel {

  private EvidencePanelModel() {}

  public enum SectionKind {
    POSITION,
    CANDIDATE,
    LOSS,
    PV,
    KNOWLEDGE,
    NEXT_ACTION
  }

  public static class Section {
    public final String id;
    public final SectionKind kind;
    public final String title;
    public final List<TeacherEvidenceChip> chips;
    public final String summary;
    public final int priority;

    public Section(
        String id,
        SectionKind kind,
        String title,
        List<TeacherEvidenceChip> chips,
        String summary,
        int priority) {
      this.id = id;
      this.kind = kind;
      this.title = title;
      this.chips = chips;
      this.summary = summary;
      this.priority = priority;
    }
  }

  public static List<Section> build(
      List<TeacherEvidenceChip> chips,
      MoveClassification classification,
      PvReport pv,
      String actualMove,
      int moveNumber) {
    List<Section> sections = new ArrayList<>();

    sections.add(
        new Section(
            "position",
            SectionKind.POSITION,
            "当前局面",
            filterChips(chips, TeacherEvidenceChip.Kind.MOVE, TeacherEvidenceChip.Kind.COORDINATE),
            "第 " + moveNumber + " 手，实战 " + (actualMove == null ? "未知" : actualMove) + "。",
            100));

    sections.add(
        new Section(
            "candidate",
            SectionKind.CANDIDATE,
            "AI 候选",
            filterChips(chips, TeacherEvidenceChip.Kind.CANDIDATE),
            firstCandidateSummary(chips),
            90));

    sections.add(
        new Section(
            "loss",
            SectionKind.LOSS,
            "损失判断",
            filterChips(chips, TeacherEvidenceChip.Kind.LOSS, TeacherEvidenceChip.Kind.CONFIDENCE),
            classification != null
                ? classification.severity
                    + "/"
                    + classification.confidence
                    + "。"
                    + (classification.shouldDeepen ? "建议加深后再做最终结论。" : "证据可用于当前讲解。")
                : "尚未生成结构化问题手分类。",
            80));

    sections.add(
        new Section(
            "pv",
            SectionKind.PV,
            "变化可信度",
            filterChips(chips, TeacherEvidenceChip.Kind.PV),
            pv != null ? pv.overall + ": " + pv.recommendedWording : "尚未生成 PV 可信度。",
            70));

    sections.add(
        new Section(
            "knowledge",
            SectionKind.KNOWLEDGE,
            "知识匹配",
            filterChips(chips, TeacherEvidenceChip.Kind.KNOWLEDGE),
            "没有强知识匹配。",
            60));

    sections.add(
        new Section(
            "next-action",
            SectionKind.NEXT_ACTION,
            "下一步",
            new ArrayList<>(),
            (classification != null && classification.shouldDeepen)
                    || (pv != null && pv.shouldDeepen)
                ? "建议加深分析或只输出保守讲解。"
                : "可以进入老师讲解、区间复盘或训练题推荐。",
            50));

    List<Section> result = new ArrayList<>();
    for (Section s : sections) {
      if (!s.chips.isEmpty() || s.kind == SectionKind.NEXT_ACTION) result.add(s);
    }
    result.sort(Comparator.comparingInt((Section s) -> s.priority).reversed());
    return result;
  }

  public static String copyText(List<Section> sections) {
    StringBuilder sb = new StringBuilder();
    for (Section s : sections) {
      sb.append("## ").append(s.title).append("\n");
      if (s.summary != null && !s.summary.isEmpty()) sb.append(s.summary).append("\n");
      for (TeacherEvidenceChip c : s.chips) {
        sb.append("- ").append(c.label);
        if (c.detail != null && !c.detail.isEmpty()) sb.append(": ").append(c.detail);
        sb.append("\n");
      }
      sb.append("\n");
    }
    return sb.toString().trim();
  }

  private static List<TeacherEvidenceChip> filterChips(
      List<TeacherEvidenceChip> chips, TeacherEvidenceChip.Kind... kinds) {
    List<TeacherEvidenceChip> out = new ArrayList<>();
    for (TeacherEvidenceChip c : chips) {
      for (TeacherEvidenceChip.Kind k : kinds) if (c.kind == k) out.add(c);
    }
    return out;
  }

  private static String firstCandidateSummary(List<TeacherEvidenceChip> chips) {
    for (TeacherEvidenceChip c : chips) {
      if (c.kind == TeacherEvidenceChip.Kind.CANDIDATE)
        return c.detail != null ? c.detail : c.label;
    }
    return "暂无 AI 候选点。";
  }
}
