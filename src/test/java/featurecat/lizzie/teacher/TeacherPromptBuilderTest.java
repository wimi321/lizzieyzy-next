package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class TeacherPromptBuilderTest {
  @Test
  void localizedRequestsDoNotAskForExhaustiveVariationReports() {
    var position = TeacherEvidence.current(TeacherBoardContextTest.position()).orElseThrow();
    for (String tag : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
      var request = TeacherPromptBuilder.forPosition(position, Locale.forLanguageTag(tag), null);
      String instruction = request.get(1).content.split("【KataGo evidence】")[0];
      assertFalse(instruction.contains("PV"), tag + ": " + instruction);
      assertFalse(instruction.contains("Follow each"), tag);
      assertTrue(request.get(0).content.contains("2-4 short paragraphs"), tag);
    }
  }

  @Test
  void promptContainsEvidenceAndExplicitAntiHallucinationContract() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            42,
            "B",
            1600,
            "D4",
            OptionalDouble.of(2.5),
            List.of(
                new TeacherEvidence.Candidate(1, "Q16", 62.4, 3.1, 1000, List.of("Q16", "D4")),
                new TeacherEvidence.Candidate(2, "D4", 59.9, 1.4, 600, List.of("D4", "Q16"))));

    List<TeacherLlmClient.Message> messages =
        TeacherPromptBuilder.forPosition(position, Locale.SIMPLIFIED_CHINESE, null);
    String system = messages.get(0).content;
    String evidence = messages.get(1).content;

    assertTrue(system.contains("简体中文"));
    assertTrue(system.contains("禁止编造"));
    assertTrue(system.contains("世界顶尖围棋职业棋手"));
    assertTrue(system.contains("不是用个人偏好替代 KataGo"));
    assertTrue(system.contains("0.5 目以内视为基本等价"));
    assertTrue(system.contains("超过 1.5 目再重点解释"));
    assertTrue(system.contains("胜率仅作辅助"));
    assertTrue(system.contains("教学性解读"));
    assertTrue(evidence.contains("第 42 手之后的局面"));
    assertTrue(system.contains("不得进行作弊指控"));
    assertTrue(system.contains("不得声称用户具有任何官方段位"));
    assertTrue(system.contains("不强制附加练习报告"));
    assertTrue(system.contains("Teaching contract"));
    assertTrue(evidence.contains("D4"));
    assertTrue(evidence.contains("Q16"));
    assertTrue(evidence.contains("pv=Q16"));
    assertFalse(evidence.contains("pv=Q16(B) Q16(W)"));
    assertTrue(evidence.contains("2.5"));
    assertFalse(evidence.contains("user comment"));
  }

  @Test
  void playedMoveWarningUsesARealLineBreak() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            9,
            "W",
            800,
            "C3",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 60, 2, 800, List.of("Q16"))));

    String evidence =
        TeacherPromptBuilder.forPosition(position, Locale.ENGLISH, null).get(1).content;

    assertTrue(evidence.contains("Position after move 9"));
    assertTrue(evidence.contains("Actual next move: C3"));
    assertTrue(evidence.contains("Candidate #1: move=Q16"));
    assertTrue(evidence.contains("C3"));
    assertTrue(evidence.contains("\n"));
    assertFalse(evidence.contains("list.\\\\n"));
  }

  @Test
  void followUpKeepsTheOriginalRangeEvidenceInsteadOfOnlyTheLastPosition() {
    List<TeacherLlmClient.Message> evidenceContext =
        List.of(
            new TeacherLlmClient.Message("system", "evidence-only"),
            new TeacherLlmClient.Message("user", "move 12 evidence\nmove 38 evidence"));

    List<TeacherLlmClient.Message> followUp =
        TeacherPromptBuilder.forFollowUp(
            evidenceContext, "previous answer", "Why was move 12 important?", Locale.ENGLISH, null);

    assertEquals(4, followUp.size());
    assertEquals("move 12 evidence\nmove 38 evidence", followUp.get(1).content);
    assertEquals("previous answer", followUp.get(2).content);
    assertTrue(followUp.get(3).content.contains("Why was move 12 important?"));
  }

  @Test
  void nonChinesePromptsRemainLocalizedAndKeepSafetyBoundaries() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(
            9,
            "W",
            800,
            "C3",
            OptionalDouble.empty(),
            List.of(new TeacherEvidence.Candidate(1, "Q16", 60, 2, 800, List.of("Q16"))));

    List<TeacherLlmClient.Message> messages =
        TeacherPromptBuilder.forPosition(position, Locale.ENGLISH, snapshot("d", 3));

    assertTrue(messages.get(0).content.contains("Reply in English"));
    assertTrue(messages.get(0).content.contains("world-class professional Go player"));
    assertTrue(messages.get(0).content.contains("not to replace KataGo"));
    assertTrue(messages.get(0).content.contains("within 0.5 points"));
    assertTrue(messages.get(0).content.contains("more than 1.5"));
    assertTrue(messages.get(0).content.contains("Never invent"));
    assertTrue(messages.get(0).content.contains("Do not make cheating accusations"));
    assertTrue(messages.get(0).content.contains("final values normalized by the application"));
    assertTrue(messages.get(0).content.contains("do not infer another perspective"));
    assertFalse(messages.get(0).content.contains("black-positive convention"));
    assertFalse(messages.get(0).content.contains("围棋 AI 讲棋老师"));
    assertTrue(messages.get(1).content.startsWith("Explain one important decision"));
    assertFalse(messages.get(1).content.contains("讲解实战下一手"));
  }

  @Test
  void chinesePersonaMapsEveryRankBandWithoutDowngradingDanPlayers() {
    String oneDan = systemPrompt(snapshot("d", 1));
    String threeKyu = systemPrompt(snapshot("k", 3));
    String sevenKyu = systemPrompt(snapshot("k", 7));
    String twelveKyu = systemPrompt(snapshot("k", 12));

    assertTrue(oneDan.contains("当前用户是段位水平"));
    assertTrue(oneDan.contains("当前用户段位：1d"));
    assertTrue(threeKyu.contains("当前用户是高级水平"));
    assertTrue(sevenKyu.contains("当前用户是级位/中级水平"));
    assertTrue(twelveKyu.contains("当前用户是入门水平"));
  }

  @Test
  void traditionalChineseRequestsTraditionalOutputWithoutDroppingPersona() {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(1, "B", 100, "", OptionalDouble.empty(), List.of());

    String system =
        TeacherPromptBuilder.forPosition(position, Locale.TRADITIONAL_CHINESE, snapshot("k", 5))
            .get(0)
            .content;

    assertTrue(system.contains("繁體中文"));
    assertTrue(system.contains("【风格设置】"));
    assertTrue(system.contains("禁止编造"));
    assertTrue(system.contains("应用已经处理好的最终值"));
    assertTrue(system.contains("不得自行推测其他视角"));
  }

  @Test
  void everySupportedLocaleProvidesValidPromptTemplates() {
    List<Locale> locales =
        List.of(
            Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("th-TH"));

    for (Locale locale : locales) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertTrue(bundle.containsKey("Teacher.status.evidenceReady"), locale.toLanguageTag());
      assertTrue(bundle.containsKey("Teacher.progress.accessible"), locale.toLanguageTag());
      assertTrue(
          java.text.MessageFormat.format(bundle.getString("Teacher.prompt.positionAfter"), 42)
              .contains("42"),
          locale.toLanguageTag());
      assertTrue(
          java.text.MessageFormat.format(bundle.getString("Teacher.prompt.winrateLoss"), "2.5")
              .contains("2.5"),
          locale.toLanguageTag());
    }
  }

  private static String systemPrompt(TeacherSettings.Snapshot snapshot) {
    TeacherEvidence.Position position =
        new TeacherEvidence.Position(1, "B", 100, "", OptionalDouble.empty(), List.of());
    return TeacherPromptBuilder.forPosition(position, Locale.SIMPLIFIED_CHINESE, snapshot)
        .get(0)
        .content;
  }

  private static TeacherSettings.Snapshot snapshot(String rankMode, int rankNum) {
    return new TeacherSettings.Snapshot(
        "https://example.com/v1",
        "test-model",
        false,
        false,
        false,
        "none",
        rankMode,
        rankNum,
        0,
        1,
        1,
        1);
  }
}
