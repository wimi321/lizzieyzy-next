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
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.currentPosition", "当前局面"),
            filterChips(chips, TeacherEvidenceChip.Kind.MOVE, TeacherEvidenceChip.Kind.COORDINATE),
            java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.movePlayed", "第 {0} 手，实战 {1}。"), moveNumber, actualMove == null ? featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.unknown", "未知") : actualMove),
            100));

    sections.add(
        new Section(
            "candidate",
            SectionKind.CANDIDATE,
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.aiCandidates", "AI 候选"),
            filterChips(chips, TeacherEvidenceChip.Kind.CANDIDATE),
            firstCandidateSummary(chips),
            90));

    sections.add(
        new Section(
            "loss",
            SectionKind.LOSS,
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.lossJudge", "损失判断"),
            filterChips(chips, TeacherEvidenceChip.Kind.LOSS, TeacherEvidenceChip.Kind.CONFIDENCE),
            classification != null
                ? classification.severity
                    + "/"
                    + classification.confidence
                    + "。"
                    + (classification.shouldDeepen ? featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.deepenAdvice", "建议加深后再做最终结论。") : featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.evidenceReady", "证据可用于当前讲解。"))
                : featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.noClassification", "尚未生成结构化问题手分类。"),
            80));

    sections.add(
        new Section(
            "pv",
            SectionKind.PV,
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.pvConfidence", "变化可信度"),
            filterChips(chips, TeacherEvidenceChip.Kind.PV),
            pv != null ? pv.overall + ": " + pv.recommendedWording : featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.noPvConfidence", "尚未生成 PV 可信度。"),
            70));

    sections.add(
        new Section(
            "knowledge",
            SectionKind.KNOWLEDGE,
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.knowledgeMatch", "知识匹配"),
            filterChips(chips, TeacherEvidenceChip.Kind.KNOWLEDGE),
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.noKnowledge", "没有强知识匹配。"),
            60));

    sections.add(
        new Section(
            "next-action",
            SectionKind.NEXT_ACTION,
            featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.nextStep", "下一步"),
            new ArrayList<>(),
            (classification != null && classification.shouldDeepen)
                    || (pv != null && pv.shouldDeepen)
                ? featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.deepenOrConservative", "建议加深分析或只输出保守讲解。")
                : featurecat.lizzie.teacher.TeacherI18n.t("EvidencePanelModel.enterTeaching", "可以进入老师讲解、区间解说或训练题推荐。"),
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
