package featurecat.lizzie.teacher;

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
        new TeacherLlmClient.Message("system", systemPrompt(locale, snapshot)),
        new TeacherLlmClient.Message(
            "user",
            modeInstruction(Mode.NEXT_MOVE)
                + "\n\n【KataGo evidence】\n"
                + formatPosition(position)));
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
      evidence.append('\n').append(formatPosition(position));
    }
    return List.of(
        new TeacherLlmClient.Message("system", systemPrompt(locale, snapshot)),
        new TeacherLlmClient.Message(
            "user", modeInstruction(mode) + "\n\n【KataGo evidence】\n" + evidence));
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
            "user", modeInstruction(Mode.FOLLOW_UP) + "\n\n" + question.trim()));
    return List.copyOf(messages);
  }

  static String formatPosition(TeacherEvidence.Position position) {
    StringBuilder text = new StringBuilder();
    text.append("Position after move ").append(position.moveNumber).append('\n');
    text.append("Side to play: ").append(position.toPlay).append('\n');
    text.append("Root visits: ").append(position.playouts).append('\n');
    text.append("Actual next move: ")
        .append(position.actualMove.isEmpty() ? "not available" : position.actualMove)
        .append('\n');
    if (position.actualWinrateLoss.isPresent()) {
      text.append("Actual move winrate loss versus top candidate: ")
          .append(format(position.actualWinrateLoss.getAsDouble()))
          .append(" percentage points\n");
    }
    if (!position.playedContinuation.isEmpty()) {
      text.append("Played continuation: ")
          .append(String.join(" ", position.playedContinuation))
          .append('\n');
    }
    for (TeacherEvidence.Candidate candidate : position.candidates) {
      text.append("Candidate #")
          .append(candidate.rank)
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
      if (!candidate.variation.isEmpty()) {
        text.append(", pv=");
        boolean black = "B".equals(position.toPlay);
        text.append(candidate.coordinate).append(black ? "(B)" : "(W)");
        for (String variationMove : candidate.variation) {
          black = !black;
          text.append(" ").append(variationMove).append(black ? "(B)" : "(W)");
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
      text.append("Note: the played move ")
          .append(position.actualMove)
          .append(" is NOT among the top candidates above; compare it against the list.\\n");
    }
    return text.toString();
  }

  private static String systemPrompt(Locale locale, TeacherSettings.Snapshot snapshot) {
    StringBuilder prompt =
        new StringBuilder("You are a careful Go review assistant. Reply in ")
            .append(outputLanguage(locale))
            .append(". Use only the supplied KataGo evidence. Never invent coordinates, variations, ")
            .append("winrates, score leads, move intentions, or game results. If evidence is missing, ")
            .append("say so plainly. Separate facts from teaching interpretation. Do not make ")
            .append("cheating accusations or claim an official rank. Keep the explanation practical ")
            .append("and understandable.\n\n");
    if (snapshot != null) {
      prompt.append(teachingPersona(snapshot)).append("\n");
    }
    prompt
        .append(
            "Refer to the person naturally in the answer without role labels such as \"student\", ")
        .append("\"teacher\" or \"coach\".\n");
    prompt
        .append(
            "Perspective note: winrate and scoreLead are already given from the side-to-play ")
        .append(
            "point of view as final values; use them as-is, do not convert or flip them. ")
        .append("Score lead uses the black-positive convention.\n");
    return prompt.toString();
  }

  /** 讲解设置（等级/风格/术语密度/节奏/变化细节）→ persona 指令。 */
  private static String teachingPersona(TeacherSettings.Snapshot s) {
    StringBuilder persona = new StringBuilder();
    String rank;
    if ("d".equals(s.rankMode)) {
      rank =
          s.rankNum >= 4
              ? "strong dan player"
              : s.rankNum >= 1
                  ? "advanced amateur (dan level)"
                  : "advanced amateur";
    } else {
      rank =
          s.rankNum >= 10
              ? "beginner"
              : s.rankNum >= 5
                  ? "intermediate amateur"
                  : "advanced amateur (single-digit kyu)";
    }
    persona
        .append("The student is a ")
        .append(rank)
        .append(" (rank ")
        .append(s.rankMode)
        .append(s.rankNum)
        .append("). Adjust the depth of the explanation to that level; do not state the rank ")
        .append("in the answer. ");
    switch (Math.max(0, Math.min(4, s.styleIndex))) {
      case 1:
        persona.append("Be rigorous and structured; present complete evidence before conclusions. ");
        break;
      case 2:
        persona.append("Be patient and encouraging, guiding like a friendly coach. ");
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

  private static String modeInstruction(Mode mode) {
    switch (mode) {
      case RANGE:
        return "Review the selected move range. Focus on the most important turning points, "
            + "compare the actual move with KataGo's candidates, follow only the supplied PVs, "
            + "and finish with three actionable lessons.";
      case WHOLE_GAME:
        return "Review the whole game from the selected key positions. Give a short overview, "
            + "the decisive turning points in chronological order, and three actionable lessons. "
            + "Do not pretend that omitted positions were analyzed.";
      case FOLLOW_UP:
        return "Answer the follow-up using the same evidence. If the question needs information "
            + "that is not present, explain what additional KataGo analysis is required.";
      case NEXT_MOVE:
      default:
        return "Explain the actual next move when available and compare it with KataGo's top "
            + "three candidates. Follow each supplied PV move by move, then give one practical "
            + "principle. If there is no actual next move, explain only the candidates.";
    }
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
