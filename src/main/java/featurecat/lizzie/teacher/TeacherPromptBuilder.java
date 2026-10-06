package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.TeacherPersona;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds bounded, evidence-only prompts from immutable KataGo snapshots. */
final class TeacherPromptBuilder {
  enum Mode {
    NEXT_MOVE,
    RANGE,
    WHOLE_GAME,
    FOLLOW_UP
  }

  private static final DecimalFormat ONE_DECIMAL =
      new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));

  private TeacherPromptBuilder() {}

  static List<TeacherLlmClient.Message> forPosition(
      TeacherEvidence.Position position, Locale locale, TeacherSettings.Snapshot snapshot) {
    return List.of(
        new TeacherLlmClient.Message("system", systemPrompt(locale, snapshot) + teachingContract()),
        new TeacherLlmClient.Message(
            "user",
            modeInstruction(Mode.NEXT_MOVE, locale)
                + "\n\n【KataGo evidence】\n"
                + formatPosition(position, locale)));
  }

  static List<TeacherLlmClient.Message> forRange(
      TeacherEvidence.Range range, Mode mode, Locale locale, TeacherSettings.Snapshot snapshot) {
    StringBuilder evidence = new StringBuilder();
    evidence
        .append("Analyzed positions: ")
        .append(range.analyzedPositions)
        .append("; selected key positions: ")
        .append(range.positions.size());
    if (range.omittedPositions > 0) {
      evidence.append("; omitted lower-priority positions: ").append(range.omittedPositions);
    }
    evidence.append('\n');
    for (TeacherEvidence.Position position : range.positions) {
      evidence.append('\n').append(formatPosition(position, locale));
    }
    return List.of(
        new TeacherLlmClient.Message("system", systemPrompt(locale, snapshot) + teachingContract()),
        new TeacherLlmClient.Message(
            "user", modeInstruction(mode, locale) + "\n\n【KataGo evidence】\n" + evidence));
  }

  static List<TeacherLlmClient.Message> forFollowUp(
      List<TeacherLlmClient.Message> evidenceContext,
      String previousAnswer,
      String question,
      Locale locale,
      TeacherSettings.Snapshot snapshot) {
    ArrayList<TeacherLlmClient.Message> messages = new ArrayList<>();
    if (evidenceContext != null) {
      messages.addAll(evidenceContext);
    }
    if (messages.isEmpty()) {
      messages.add(new TeacherLlmClient.Message("system", systemPrompt(locale, snapshot)));
    }
    String boundedAnswer = previousAnswer == null ? "" : previousAnswer.trim();
    if (boundedAnswer.length() > 12_000) {
      boundedAnswer = boundedAnswer.substring(boundedAnswer.length() - 12_000);
    }
    if (!boundedAnswer.isEmpty()) {
      messages.add(new TeacherLlmClient.Message("assistant", boundedAnswer));
    }
    messages.add(
        new TeacherLlmClient.Message(
            "user", modeInstruction(Mode.FOLLOW_UP, locale) + "\n\n" + question.trim()));
    return List.copyOf(messages);
  }

  static String formatPosition(TeacherEvidence.Position position) {
    return formatPosition(position, TeacherStrings.locale());
  }

  static String formatPosition(TeacherEvidence.Position position, Locale locale) {
    StringBuilder text = new StringBuilder();
    text.append(
            TeacherStrings.format(
                locale,
                "Teacher.prompt.positionAfter",
                "Position after move {0}",
                position.moveNumber))
        .append('\n');
    text.append(
            TeacherStrings.format(
                locale, "Teacher.prompt.sideToPlay", "Side to play: {0}", position.toPlay))
        .append('\n');
    text.append(
            TeacherStrings.format(
                locale, "Teacher.prompt.rootVisits", "Root visits: {0}", position.playouts))
        .append('\n');
    text.append(
            TeacherStrings.format(
                locale,
                "Teacher.prompt.actualMove",
                "Actual next move: {0}",
                position.actualMove.isEmpty()
                    ? TeacherStrings.get(locale, "Teacher.prompt.notAvailable", "not available")
                    : position.actualMove))
        .append('\n');
    if (position.actualWinrateLoss.isPresent()) {
      text.append(
              TeacherStrings.format(
                  locale,
                  "Teacher.prompt.winrateLoss",
                  "Actual move winrate loss versus top candidate: {0} percentage points",
                  format(position.actualWinrateLoss.getAsDouble())))
          .append("\n");
    }
    if (position.board == null && !position.playedContinuation.isEmpty()) {
      text.append(
              TeacherStrings.format(
                  locale,
                  "Teacher.prompt.playedContinuation",
                  "Played continuation: {0}",
                  String.join(" ", position.playedContinuation)))
          .append('\n');
    }
    for (TeacherEvidence.Candidate candidate : position.candidates) {
      text.append(
              TeacherStrings.format(
                  locale, "Teacher.prompt.candidateRank", "Candidate #{0}", candidate.rank))
          .append(": move=")
          .append(candidate.coordinate)
          .append(", visits=")
          .append(candidate.visits);
      if (Double.isFinite(candidate.winrate)) {
        text.append(", winrate=").append(format(candidate.winrate)).append('%');
      }
      if (Double.isFinite(candidate.scoreLead)) {
        text.append(", scoreLead=").append(format(candidate.scoreLead));
      }
      if (position.board == null && !candidate.variation.isEmpty()) {
        text.append(", pv=");
        boolean black = "B".equals(position.toPlay);
        String blackLabel = TeacherStrings.get(locale, "Teacher.prompt.black", "(B)");
        String whiteLabel = TeacherStrings.get(locale, "Teacher.prompt.white", "(W)");
        for (int index = 0; index < candidate.variation.size(); index++) {
          String variationMove = candidate.variation.get(index);
          if (index > 0) {
            text.append(' ');
          }
          text.append(variationMove).append(black ? blackLabel : whiteLabel);
          black = !black;
        }
      }
      text.append('\n');
    }
    if (!position.actualMove.isEmpty()
        && position.candidates.stream()
            .noneMatch(
                candidate ->
                    candidate.coordinate != null
                        && candidate.coordinate.equalsIgnoreCase(position.actualMove))) {
      text.append(
              TeacherStrings.format(
                  locale,
                  "Teacher.prompt.notInCandidates",
                  "Note: the played move {0} is NOT among the top candidates above; compare it against the list.",
                  position.actualMove))
          .append("\n");
    }
    position
        .actualScoreLoss()
        .ifPresent(
            loss ->
                text.append("Actual move score loss (points, NOT percent): ")
                    .append(format(loss))
                    .append('\n'));
    if (position.board != null) text.append('\n').append(position.board.facts());
    return text.toString();
  }

  private static String teachingContract() {
    return "\n\nTeaching contract (takes precedence over generic reporting style):\n"
        + "Teach one concrete decision per position: what needs attention, why the recorded move "
        + "allows a specific reply, how the recommended line changes the result, and one reusable "
        + "thinking question WITH its conditions. Anchor every reason to named stones/groups and "
        + "the supplied replay. Do not invent a player's intentions. If no recorded move is available, "
        + "explain a plan without inventing a mistake. A normal move does not need criticism.\n"
        + "Lead with board reasoning, not winrate. Do not enumerate candidate statistics or visits; "
        + "the UI already provides them under Analysis evidence. Mention at most one numerical loss "
        + "when it changes the lesson. Avoid generic advice like do more life-and-death problems. "
        + "Default to 2-4 short paragraphs (about 180-300 Chinese characters or 100-160 English "
        + "words for one position), and at most ONE short illustrative line. No report-style "
        + "headings or move-by-move list unless asked; expand only on request. "
        + "Whole-game/range: explain only the selected moments, group recurring themes, no move-by-move report.\n"
        + "Board groups and liberties are exact geometry, NOT proof of death, safety or sente. "
        + "Recorded continuation is what happened, NOT the best defense. Candidate lines are references, "
        + "NOT forced sequences or the engine's hidden reasoning. Only replayed prefixes may be narrated. "
        + "Do not infer ownership, territory gains, ladders or forced kills from a score difference alone. "
        + "Missing actual-move evaluation means loss is unknown; do not manufacture a number. "
        + "If a causal explanation needs an unprovided reply, state the specific missing evidence briefly "
        + "and explain only what is supported. Never pad the answer with percentages.\n";
  }

  private static String systemPrompt(Locale locale, TeacherSettings.Snapshot snapshot) {
    if (isChinese(locale)) {
      return chineseSystemPrompt(locale, snapshot);
    }

    StringBuilder prompt =
        new StringBuilder(
                "You are a world-class professional Go player and a careful, practical teacher. "
                    + "Your role is to help the user understand KataGo's evidence, not to replace "
                    + "KataGo with your personal preferred move. Reply in ")
            .append(outputLanguage(locale))
            .append(
                ". Use only the supplied KataGo evidence. Never invent coordinates, variations, ")
            .append(
                "winrates, score leads, move intentions, or game results. If evidence is missing, ")
            .append("say so plainly. Separate facts from teaching interpretation. Do not make ")
            .append(
                "cheating accusations or claim an official rank. Keep the explanation practical ")
            .append("and understandable.\n\n");
    prompt
        .append("For each selected position, explain the meaningful turning point supported by ")
        .append("a supplied reference line. Treat score differences within 0.5 points ")
        .append("as effectively equivalent for a human player, 0.5 to 1.5 as a small preference, ")
        .append("and more than 1.5 as a meaningful difference worth explaining. Use winrate only ")
        .append("as supporting context. Compare the candidates' strategic tradeoffs, and clearly ")
        .append("label any teaching interpretation that is not directly stated by the evidence.\n");
    if (snapshot != null) {
      prompt.append(teachingPersona(snapshot)).append("\n");
    }
    prompt
        .append(
            "Refer to the person naturally in the answer without role labels such as \"student\", ")
        .append("\"teacher\" or \"coach\".\n");
    prompt
        .append("Perspective note: winrate and scoreLead are final values normalized by the ")
        .append("application. Use them exactly as supplied; do not infer another perspective, ")
        .append("convert them, or flip their sign.\n");
    return prompt.toString();
  }

  private static String chineseSystemPrompt(Locale locale, TeacherSettings.Snapshot snapshot) {
    StringBuilder prompt = new StringBuilder();
    prompt
        .append("你是一位世界顶尖围棋职业棋手，同时也是一位耐心、严谨的围棋教师。\n")
        .append("你的任务是帮助用户理解 KataGo 为什么这样判断，而不是用个人偏好替代 KataGo 的结论。\n");
    if (snapshot != null) {
      prompt.append(buildChinesePersona(snapshot)).append("\n");
    }
    prompt
        .append("只能依据提供的 KataGo 分析证据进行讲解。禁止编造坐标、变化、胜率、")
        .append("目差、落子意图或比赛结果；证据不足时必须坦诚说明。事实与教学性解读要明确区分。\n")
        .append("不得进行作弊指控，也不得声称用户具有任何官方段位。\n")
        .append("指出关键手、问题手与更好的应对，语言通俗、具体且可执行。\n");
    prompt.append("分析要求：\n");
    prompt.append("1) 选一条能说明原因的参考变化，只讲关键应手和可验证的后果，不必逐手念坐标；\n");
    prompt.append("2) 以目差为主要判断依据：0.5 目以内视为基本等价，0.5 至 1.5 目只说明细微倾向，超过 1.5 目再重点解释；\n");
    prompt.append("3) 胜率仅作辅助，不因 50% 附近的小幅波动制造虚假优劣感；\n");
    prompt.append("4) 比较各候选的策略差异；棋理和意图属于教学性解读时，必须与数据事实明确区分。\n");
    prompt.append("讲解格式要求：\n");
    prompt.append("1) 先用通俗语言讲解这一手的好坏与原因；\n");
    prompt.append("2) 用简短段落讲清本局的判断重点、实战与推荐变化的差别，以及下次遇到相似局面应检查什么；\n");
    prompt.append("3) 不强制附加练习报告；不要用多做死活题、多看高手棋谱等泛泛建议凑数。\n");
    prompt.append("讲解正文严禁出现\"围棋老师\"、\"讲棋老师\"、\"教练\"等称呼。\n");
    prompt.append("不要在回答中提及用户的段位。\n");
    prompt.append("输出语言：请全程使用 ").append(chineseOutputLanguage(locale)).append("，不要混用其他语言。\n");
    prompt.append("视角说明：胜率和目差都是应用已经处理好的最终值。").append("必须按原值使用，不得自行推测其他视角、再次换算或翻转正负号。\n");
    return prompt.toString();
  }

  private static String buildChinesePersona(TeacherSettings.Snapshot s) {
    TeacherPersona.TeacherPersonaInput pin = new TeacherPersona.TeacherPersonaInput();
    pin.level = snapshotToLevel(s);
    pin.rank = snapshotToRank(s);
    pin.exactAge = null;
    pin.ageRange = TeacherPersona.AgeRange.UNKNOWN;
    pin.style = snapshotToStyle(s);
    pin.terminologyDensity = snapshotToDensity(s);
    pin.explanationPace = snapshotToPace(s);
    pin.variationDetail = snapshotToVariation(s);
    return TeacherPersona.buildTeacherPersonaInstruction(pin);
  }

  private static TeacherPersona.Level snapshotToLevel(TeacherSettings.Snapshot s) {
    if ("d".equalsIgnoreCase(s.rankMode)) {
      return TeacherPersona.Level.DAN;
    }
    if (s.rankNum >= 10) {
      return TeacherPersona.Level.BEGINNER;
    }
    if (s.rankNum >= 5) {
      return TeacherPersona.Level.INTERMEDIATE;
    }
    return TeacherPersona.Level.ADVANCED;
  }

  private static TeacherPersona.Rank snapshotToRank(TeacherSettings.Snapshot s) {
    if ("d".equalsIgnoreCase(s.rankMode)) {
      int dan = Math.max(1, Math.min(9, s.rankNum));
      return TeacherPersona.Rank.values()[dan];
    }
    return TeacherPersona.Rank.SUB1D;
  }

  private static TeacherPersona.Style snapshotToStyle(TeacherSettings.Snapshot s) {
    return switch (Math.max(0, Math.min(4, s.styleIndex))) {
      case 1 -> TeacherPersona.Style.RIGOROUS;
      case 2 -> TeacherPersona.Style.GENTLE;
      case 3 -> TeacherPersona.Style.STRICT;
      case 4 -> TeacherPersona.Style.HUMOROUS;
      default -> TeacherPersona.Style.BALANCED;
    };
  }

  private static TeacherPersona.TerminologyDensity snapshotToDensity(TeacherSettings.Snapshot s) {
    return switch (Math.max(0, Math.min(2, s.densityIndex))) {
      case 0 -> TeacherPersona.TerminologyDensity.LOW;
      case 2 -> TeacherPersona.TerminologyDensity.HIGH;
      default -> TeacherPersona.TerminologyDensity.MEDIUM;
    };
  }

  private static TeacherPersona.ExplanationPace snapshotToPace(TeacherSettings.Snapshot s) {
    return switch (Math.max(0, Math.min(2, s.paceIndex))) {
      case 0 -> TeacherPersona.ExplanationPace.BRIEF;
      case 2 -> TeacherPersona.ExplanationPace.DETAILED;
      default -> TeacherPersona.ExplanationPace.STANDARD;
    };
  }

  private static TeacherPersona.VariationDetail snapshotToVariation(TeacherSettings.Snapshot s) {
    return switch (Math.max(0, Math.min(2, s.variationIndex))) {
      case 0 -> TeacherPersona.VariationDetail.FEW;
      case 2 -> TeacherPersona.VariationDetail.MANY;
      default -> TeacherPersona.VariationDetail.MODERATE;
    };
  }

  private static String teachingPersona(TeacherSettings.Snapshot s) {
    StringBuilder persona = new StringBuilder();
    String rank;
    if ("d".equalsIgnoreCase(s.rankMode)) {
      rank =
          s.rankNum >= 4
              ? "strong dan player"
              : s.rankNum >= 1 ? "advanced amateur (dan level)" : "advanced amateur";
    } else {
      rank =
          s.rankNum >= 10
              ? "beginner"
              : s.rankNum >= 5 ? "intermediate amateur" : "advanced amateur (single-digit kyu)";
    }
    persona
        .append("The person is a ")
        .append(rank)
        .append(" (rank ")
        .append(s.rankMode)
        .append(s.rankNum)
        .append("). Adjust the depth of the explanation to that level; do not state the rank ")
        .append("in the answer. ");
    switch (Math.max(0, Math.min(4, s.styleIndex))) {
      case 1:
        persona.append(
            "Be rigorous and structured; present complete evidence before conclusions. ");
        break;
      case 2:
        persona.append("Be patient and encouraging while keeping conclusions evidence-based. ");
        break;
      case 3:
        persona.append("Be strict and direct; point out problems clearly. ");
        break;
      case 4:
        persona.append("Use vivid analogies and light humor to explain the reasoning. ");
        break;
      case 0:
      default:
        persona.append("Keep a balanced tone: explain the reasoning before the conclusion. ");
        break;
    }
    switch (Math.max(0, Math.min(2, s.densityIndex))) {
      case 0:
        persona.append("Use everyday language and avoid heavy jargon. ");
        break;
      case 2:
        persona.append("Use proper Go terminology and explain each term briefly. ");
        break;
      case 1:
      default:
        persona.append("Use Go terminology at a moderate level. ");
        break;
    }
    switch (Math.max(0, Math.min(2, s.paceIndex))) {
      case 0:
        persona.append("Keep the commentary concise and to the point. ");
        break;
      case 2:
        persona.append("Explain at a relaxed, detailed pace. ");
        break;
      case 1:
      default:
        persona.append("Keep a standard pace. ");
        break;
    }
    switch (Math.max(0, Math.min(2, s.variationIndex))) {
      case 0:
        persona.append("Mention only the essential variations. ");
        break;
      case 2:
        persona.append("Describe the important variations in detail. ");
        break;
      case 1:
      default:
        persona.append("Describe variations in moderate detail. ");
        break;
    }
    return persona.toString();
  }

  private static String modeInstruction(Mode mode, Locale locale) {
    switch (mode) {
      case RANGE:
        return TeacherStrings.get(
            locale,
            "Teacher.prompt.modeRange",
            "Review the selected move range. Focus on the most important turning points, "
                + "compare the actual move with KataGo's candidates, follow only the supplied PVs, "
                + "and combine recurring lessons without padding the answer.");
      case WHOLE_GAME:
        return TeacherStrings.get(
            locale,
            "Teacher.prompt.modeWhole",
            "Review the whole game from the selected key positions. Give a short overview, "
                + "the decisive turning points in chronological order, and practical lessons. "
                + "Do not pretend that omitted positions were analyzed.");
      case FOLLOW_UP:
        return TeacherStrings.get(
            locale,
            "Teacher.prompt.modeFollowUp",
            "Answer the follow-up using the same evidence. If the question needs information "
                + "that is not present, explain what additional KataGo analysis is required.");
      case NEXT_MOVE:
      default:
        return TeacherStrings.get(
            locale,
            "Teacher.prompt.modeNextMove",
            "Explain one important decision in 2-4 short paragraphs. Compare the actual move "
                + "with one useful reference and give a concrete reason and reusable principle. "
                + "Do not enumerate candidates or moves. Without an actual move, explain a plan.");
    }
  }

  private static boolean isChinese(Locale locale) {
    return locale == null || "zh".equals(locale.getLanguage());
  }

  private static String chineseOutputLanguage(Locale locale) {
    String country = locale == null ? "" : locale.getCountry();
    return "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) ? "繁體中文" : "简体中文";
  }

  private static String outputLanguage(Locale locale) {
    String language = locale == null ? "zh" : locale.getLanguage();
    String country = locale == null ? "" : locale.getCountry();
    if ("zh".equals(language)) {
      return "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
          ? "Traditional Chinese"
          : "Simplified Chinese";
    }
    if ("ja".equals(language)) {
      return "Japanese";
    }
    if ("ko".equals(language)) {
      return "Korean";
    }
    if ("th".equals(language)) {
      return "Thai";
    }
    return "English";
  }

  private static String format(double value) {
    synchronized (ONE_DECIMAL) {
      return ONE_DECIMAL.format(value);
    }
  }
}
