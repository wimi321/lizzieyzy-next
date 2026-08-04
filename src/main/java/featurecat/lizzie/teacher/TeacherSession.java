package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 GoAgent 的 teacher 多轮会话：system prompt 含老师风格 + 学生设定， 后续 user/assistant 轮次累积。提供把一手证据链(chips)格式化为
 * user 消息的助手方法。
 */
public class TeacherSession {
  public enum TeacherStyle {
    STRICT("严格专业的围棋教练，直指问题手，用术语"),
    FRIENDLY("亲切耐心的围棋老师，鼓励为主，循序渐进"),
    STORY("用故事和历史名局类比讲解，生动有趣");

    public final String description;

    TeacherStyle(String d) {
      this.description = d;
    }
  }

  private final List<LLMClient.Message> messages = new ArrayList<>();
  private final String studentLevel; // 段位/级别
  private final int studentAge;
  private final TeacherStyle style;

  public TeacherSession(String studentLevel, int studentAge, TeacherStyle style) {
    this.studentLevel = studentLevel == null ? "业余初段" : studentLevel;
    this.studentAge = studentAge;
    this.style = style == null ? TeacherStyle.FRIENDLY : style;
    buildSystem();
  }

  private void buildSystem() {
    String sys =
        String.format(
            "你是一个围棋 AI 讲棋老师。\n"
                + "教学对象：%s，年龄约 %d 岁。\n"
                + "你的风格：%s。\n"
                + "请基于给出的 KataGo 分析数据（胜率、目差、AI 首选、损失等）进行讲解，"
                + "指出关键手、问题手与最佳应对，语言通俗易懂、结合具体坐标。"
                + "若数据不足以判断，坦诚说明。",
            studentLevel, studentAge, style.description);
    messages.add(new LLMClient.Message("system", sys));
  }

  public void addUser(String content) {
    messages.add(new LLMClient.Message("user", content));
  }

  public void addAssistant(String content) {
    messages.add(new LLMClient.Message("assistant", content));
  }

  public List<LLMClient.Message> messages() {
    return messages;
  }

  /** 把一手的证据链 chips 格式化为自然语言段落，供作为 user 消息发送 */
  public static String chipsToText(List<TeacherEvidenceChip> chips) {
    StringBuilder sb = new StringBuilder("本手分析数据：\n");
    for (TeacherEvidenceChip c : chips) {
      sb.append("- ").append(c.label);
      if (c.detail != null && !c.detail.isEmpty()) sb.append("（").append(c.detail).append("）");
      sb.append("\n");
    }
    return sb.toString();
  }

  public void reset() {
    messages.clear();
    buildSystem();
  }
}
