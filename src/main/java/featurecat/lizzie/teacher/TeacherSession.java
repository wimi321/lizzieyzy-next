package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import featurecat.lizzie.teacher.analysis.EvidenceBundle;
import featurecat.lizzie.teacher.analysis.HumanWinrateCalibrator;
import featurecat.lizzie.teacher.analysis.TeacherPersona;
import featurecat.lizzie.teacher.analysis.VisionEvidence;
import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 GoAgent 的 teacher 多轮会话：system prompt 含老师风格 + 学生设定 + 视觉证据指令 +
 * 防编造证据包；后续 user/assistant 轮次累积。提供把一手证据链(chips)格式化为 user 消息的助手方法。
 */
public class TeacherSession {

  public enum TeacherStyle {
    BALANCED("平衡自然，先讲判断再讲原因"),
    RIGOROUS("严谨细致，结构清晰证据完整"),
    FRIENDLY("亲切耐心，像朋友一样引导"),
    STRICT("严格专业，直接指出问题"),
    HUMOROUS("风趣幽默，用比喻讲棋理");

    final String description;

    TeacherStyle(String d) {
      this.description = d;
    }
  }

  private final List<LLMClient.Message> messages = new ArrayList<>();
  private final String studentLevel;
  private final int studentAge;
  private final TeacherStyle style;
  private final TeacherPersona.TerminologyDensity density;
  private final TeacherPersona.ExplanationPace pace;
  private final TeacherPersona.VariationDetail variationDetail;
  private EvidenceBundle.Bundle evidenceBundle;
  private TeachingEvidenceBuilder.TeachingEvidence teachingEvidence;

  public TeacherSession(String studentLevel, int studentAge, TeacherStyle style) {
    this(studentLevel, studentAge, style, TeacherPersona.TerminologyDensity.MEDIUM, TeacherPersona.ExplanationPace.STANDARD, TeacherPersona.VariationDetail.MODERATE);
  }

  /** 完整构造：段位/年龄/风格/术语密度/讲解节奏/变化细节（对齐 GoAgent TeacherPersonaInput 7 参数，年龄外全可配） */
  public TeacherSession(String studentLevel, int studentAge, TeacherStyle style,
                        TeacherPersona.TerminologyDensity density,
                        TeacherPersona.ExplanationPace pace,
                        TeacherPersona.VariationDetail variationDetail) {
    this.studentLevel = studentLevel == null ? "业余初段" : studentLevel;
    this.studentAge = studentAge;
    this.style = style == null ? TeacherStyle.FRIENDLY : style;
    this.density = density == null ? TeacherPersona.TerminologyDensity.MEDIUM : density;
    this.pace = pace == null ? TeacherPersona.ExplanationPace.STANDARD : pace;
    this.variationDetail = variationDetail == null ? TeacherPersona.VariationDetail.MODERATE : variationDetail;
    this.evidenceBundle = null;
    buildSystem();
  }

  /** 设置当前手的防编造证据包（讲解前调用） */
  public void setEvidenceBundle(EvidenceBundle.Bundle b) { this.evidenceBundle = b; }
  public void setTeachingEvidence(TeachingEvidenceBuilder.TeachingEvidence ev) { this.teachingEvidence = ev; }
  public TeachingEvidenceBuilder.TeachingEvidence getTeachingEvidence() { return this.teachingEvidence; }

  public static HumanWinrateCalibrator.Level toCalibratorLevel(String level) {
    if (level == null) return HumanWinrateCalibrator.Level.INTERMEDIATE;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(level);
    int num = 0;
    if (m.find()) { try { num = Integer.parseInt(m.group(1)); } catch (Exception ignore) {} }
    if (level.contains("级") || level.contains("k") || level.contains("K")) {
      // 级位（网棋 18k~1k）：数字越大水平越低
      if (num >= 15) return HumanWinrateCalibrator.Level.BEGINNER;   // 15-18k 入门
      if (num >= 8) return HumanWinrateCalibrator.Level.BEGINNER;    // 8-14k 初级
      if (num >= 1) return HumanWinrateCalibrator.Level.INTERMEDIATE; // 1-7k 中级
      return HumanWinrateCalibrator.Level.BEGINNER;
    }
    // 段位（网棋 1d~9d）：精确映射
    if (num <= 3) return HumanWinrateCalibrator.Level.INTERMEDIATE;
    if (num <= 6) return HumanWinrateCalibrator.Level.ADVANCED;
    return HumanWinrateCalibrator.Level.DAN;
  }

  /** UI 段位选项 → GoAgent 学生段位 rank（sub1d~9d），供 rankInstruction 使用 */
  public static TeacherPersona.Rank toStudentRank(String level) {
    if (level == null) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(level);
    int num = 0;
    if (m.find()) { try { num = Integer.parseInt(m.group(1)); } catch (Exception ignore) {} }
    if (level.contains("级") || level.contains("k") || level.contains("K")) {
      // 级位（网棋 18k~1k）：统一 SUB1D（1段以下）
      return TeacherPersona.Rank.SUB1D;
    }
    // 段位：精确映射 D1-D9
    if (num >= 9) return TeacherPersona.Rank.D9;
    if (num == 8) return TeacherPersona.Rank.D8;
    if (num == 7) return TeacherPersona.Rank.D7;
    if (num == 6) return TeacherPersona.Rank.D6;
    if (num == 5) return TeacherPersona.Rank.D5;
    if (num == 4) return TeacherPersona.Rank.D4;
    if (num == 3) return TeacherPersona.Rank.D3;
    if (num == 2) return TeacherPersona.Rank.D2;
    if (num == 1) return TeacherPersona.Rank.D1;
    if (level.contains("段")) return TeacherPersona.Rank.D1;
    return null;
  }

  public static TeacherPersona.Level toPersonaLevel(HumanWinrateCalibrator.Level lv) {
    return switch (lv) {
      case BEGINNER -> TeacherPersona.Level.BEGINNER;
      case INTERMEDIATE -> TeacherPersona.Level.INTERMEDIATE;
      case ADVANCED -> TeacherPersona.Level.ADVANCED;
      case DAN -> TeacherPersona.Level.DAN;
    };
  }

  public static TeacherPersona.Style toPersonaStyle(TeacherStyle st) {
    return switch (st) {
      case RIGOROUS -> TeacherPersona.Style.RIGOROUS;
      case STRICT -> TeacherPersona.Style.STRICT;
      case FRIENDLY -> TeacherPersona.Style.GENTLE;
      case HUMOROUS -> TeacherPersona.Style.HUMOROUS;
      default -> TeacherPersona.Style.BALANCED;
    };
  }

  private void buildSystem() {
    TeacherPersona.Style ps = toPersonaStyle(style);
    HumanWinrateCalibrator.Level lv = toCalibratorLevel(studentLevel);
    TeacherPersona.Level pl = toPersonaLevel(lv);
    TeacherPersona.TeacherPersonaInput pin = new TeacherPersona.TeacherPersonaInput();
    pin.level = pl; pin.rank = toStudentRank(studentLevel); pin.exactAge = studentAge > 0 ? studentAge : null;
    pin.ageRange = TeacherPersona.AgeRange.UNKNOWN;
    pin.style = ps;
    pin.terminologyDensity = density;
    pin.explanationPace = pace;
    pin.variationDetail = variationDetail;
    String persona = TeacherPersona.buildTeacherPersonaInstruction(pin);
    String vision = VisionEvidence.systemInstruction(false);
    StringBuilder sb = new StringBuilder();
    sb.append("你是一位优秀的围棋教师。\n");
    sb.append(persona).append("\n");
    sb.append(vision).append("\n");
    sb.append("请基于给出的 KataGo 分析数据（胜率、目差、AI 首选、损失、知识匹配等）进行讲解，");
    sb.append("指出关键手、问题手与最佳应对，语言通俗易懂、结合具体坐标。\n");
    sb.append("\\n");
    sb.append("**角色设定：**\\n");
    sb.append("你是一位世界顶尖围棋职业棋手，同时也是一位优秀的围棋教师。你的任务是帮助一位业余爱好者理解KataGo给出的分析结果，让他真正看懂每一步棋背后的逻辑。\\n");
    sb.append("请忠实于KataGo给出的分析结果进行解读，而非推荐你个人的下法。你的角色是帮助用户理解AI为什么这么推荐，而不是替AI做决定。\\n\\n");
    sb.append("**分析要求：**\\n");
    sb.append("请对给出的分析数据进行讲解，每个选点的分析必须包含以下内容：\\n\\n");
    sb.append("**1. 逐手追踪变化图**\\n");
    sb.append("- 按照变化图中的手顺编号，逐步说明每手棋在做什么\\n");
    sb.append("- 标注关键转折点（比如：在哪一手，局面发生了质变）\\n");
    sb.append("- 说明最终结果：谁得了什么，谁亏了什么\\n\\n");
    sb.append("**2. 胜率与目差解读**\\n");
    sb.append("- 胜率和目差并列呈现，作为判断依据\\n");
    sb.append("- 三个选点胜率接近（如差值在 5% 以内）时，说明差异不大\\n");
    sb.append("- 目差差异很小（如 0.5 目以内）时，应告知用户差异可忽略\\n\\n");
    sb.append("**3. 棋理分析**\\n");
    sb.append("- 该选点的核心意图（攻击/防守/腾挪/弃子/脱先/扩张）\\n");
    sb.append("- 为什么KataGo推荐这手棋\\n");
    sb.append("- 变化图中存在定式或常见棋形时请指出\\n\\n");
    sb.append("**4. 与其余选点的对比**\\n");
    sb.append("- 各选点在策略上的本质区别\\n");
    sb.append("- 什么风格/局面下会选择哪一步\\n\\n");
    sb.append("**禁止事项：**\\n");
    sb.append("- 不要脱离变化图凭感觉描述\\n");
    sb.append("- 不要用应该/可能/大概等模糊表述\\n");
    sb.append("- 不要因微小目差制造虚假优劣感\\n");
    sb.append("- 所有坐标/胜率/目差必须来自证据，禁用编造\\n\\n");
    if (evidenceBundle != null) {
      sb.append("\n").append(EvidenceBundle.toPrompt(evidenceBundle));
    }
    sb.append("若数据不足以判断，坦诚说明。");
    messages.add(new LLMClient.Message("system", sb.toString()));
  }

  public void addUser(String text) {
    messages.add(new LLMClient.Message("user", text));
  }

  public void addAssistant(String text) {
    messages.add(new LLMClient.Message("assistant", text));
  }

  public List<LLMClient.Message> messages() {
    return messages;
  }

  /** 把证据分区 chips 格式化为 user 消息文本（供单手讲解）。 */
  public static String chipsToText(List<TeacherEvidenceChip> chips) {
    StringBuilder sb = new StringBuilder();
    sb.append("当前局面分析证据：\n");
    for (TeacherEvidenceChip c : chips) {
      sb.append("- ").append(c.label);
      if (c.detail != null && !c.detail.isEmpty()) sb.append("：").append(c.detail);
      sb.append("\n");
    }
    return sb.toString();
  }
}
