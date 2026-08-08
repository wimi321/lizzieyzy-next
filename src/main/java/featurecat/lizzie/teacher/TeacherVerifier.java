package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local anti-fabrication check for the AI commentary.
 *
 * <p>After the LLM finishes, verify that every coordinate mentioned as a recommendation is
 * present in the KataGo evidence (top candidates, the recorded move, or one of the supplied
 * PVs), that no impossible winrate percentages were written, and that the wording is not
 * more absolute than the evidence supports. This mirrors the teacher-side verifier from the
 * original integration while staying a small, dependency-free class.
 */
public final class TeacherVerifier {
  private static final Pattern COORDINATE =
      Pattern.compile("\\b([A-Ta-t][0-9]{1,2})\\b");
  private static final Pattern PERCENT =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern ABSOLUTE_WORDING =
      Pattern.compile(
          ".*(明显恶手|必败|唯一|绝对|certainly|only\\s+move|forced).*",
          Pattern.CASE_INSENSITIVE);

  private TeacherVerifier() {}

  public static final class Result {
    public final List<String> violations = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public boolean ok() {
      return violations.isEmpty();
    }

    public boolean hasNotes() {
      return !violations.isEmpty() || !warnings.isEmpty();
    }
  }

  /** 校验 LLM 输出：坐标必须来自证据（候选/实战手/PV），百分比不可能超 100，措辞不过度绝对。 */
  public static Result verify(String markdown, TeacherEvidence.Position position) {
    Result result = new Result();
    if (markdown == null || markdown.isBlank()) {
      return result;
    }
    Set<String> allowed = allowedCoordinates(position);
    Matcher coordinateMatcher = COORDINATE.matcher(markdown);
    while (coordinateMatcher.find()) {
      String coordinate = coordinateMatcher.group(1).toUpperCase(Locale.ROOT);
      if (!allowed.contains(coordinate)) {
        result.violations.add(
            "Unsupported coordinate "
                + coordinate
                + ": not in the top candidates, the recorded move, or the supplied PVs.");
      }
    }
    Matcher percentMatcher = PERCENT.matcher(markdown);
    while (percentMatcher.find()) {
      try {
        double value = Double.parseDouble(percentMatcher.group(1));
        if (value > 100) {
          result.violations.add("Impossible winrate percentage " + value + "%.");
        }
      } catch (NumberFormatException ignored) {
        // Non-numeric match is impossible by the pattern; ignore defensively.
      }
    }
    if (ABSOLUTE_WORDING.matcher(markdown).matches()) {
      result.warnings.add(
          "The wording sounds too absolute; soften it unless the evidence clearly supports it.");
    }
    return result;
  }

  /** 证据允许的坐标集合：候选点 + 实战手 + 各候选 PV 序列。 */
  private static Set<String> allowedCoordinates(TeacherEvidence.Position position) {
    Set<String> allowed = new HashSet<>();
    if (position == null) {
      return allowed;
    }
    if (position.actualMove != null && !position.actualMove.isEmpty()) {
      allowed.add(position.actualMove.toUpperCase(Locale.ROOT));
    }
    for (TeacherEvidence.Candidate candidate : position.candidates) {
      if (candidate.coordinate != null && !candidate.coordinate.isEmpty()) {
        allowed.add(candidate.coordinate.toUpperCase(Locale.ROOT));
      }
      if (candidate.variation != null) {
        for (String move : candidate.variation) {
          if (move != null && !move.isEmpty()) {
            allowed.add(move.toUpperCase(Locale.ROOT));
          }
        }
      }
    }
    return allowed;
  }
}
