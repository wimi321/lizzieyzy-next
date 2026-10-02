package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local anti-fabrication check for the AI commentary.
 *
 * <p>After the LLM finishes, verify that every coordinate mentioned as a recommendation is present
 * in the KataGo evidence (top candidates, the recorded move, or one of the supplied PVs), that no
 * impossible winrate percentages were written, and that the wording is not more absolute than the
 * evidence supports. This mirrors the teacher-side verifier from the original integration while
 * staying a small, dependency-free class.
 */
public final class TeacherVerifier {
  private static final Pattern COORDINATE =
      Pattern.compile("(?<![A-Za-z0-9])([A-HJ-Za-hj-z](?:[1-9]|1[0-9]|2[0-5]))(?![A-Za-z0-9])");
  private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern ABSOLUTE_WORDING =
      Pattern.compile(
          ".*(明显恶手|必败|唯一|绝对|certainly|only\\s+move|forced).*", Pattern.CASE_INSENSITIVE);

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
    return verify(markdown, position == null ? List.of() : List.of(position));
  }

  /**
   * Verifies commentary generated from one or more positions.
   *
   * <p>Range and whole-game reviews legitimately mention coordinates from several positions. An
   * empty position list therefore disables coordinate validation while retaining the independent
   * percentage and wording checks.
   */
  public static Result verify(String markdown, Collection<TeacherEvidence.Position> positions) {
    Result result = new Result();
    if (markdown == null || markdown.isBlank()) {
      return result;
    }
    Set<String> allowed = allowedCoordinates(positions);
    if (!allowed.isEmpty()) {
      Matcher coordinateMatcher = COORDINATE.matcher(markdown);
      while (coordinateMatcher.find()) {
        String coordinate = coordinateMatcher.group(1).toUpperCase(Locale.ROOT);
        if (!allowed.contains(coordinate)) {
          result.violations.add(
              "Unsupported coordinate "
                  + coordinate
                  + ": not in the supplied KataGo or game-record evidence.");
        }
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
  private static Set<String> allowedCoordinates(Collection<TeacherEvidence.Position> positions) {
    Set<String> allowed = new HashSet<>();
    if (positions == null) {
      return allowed;
    }
    for (TeacherEvidence.Position position : positions) {
      if (position == null) {
        continue;
      }
      addCoordinate(allowed, position.actualMove);
      if (position.board != null) {
        for (TeacherBoardContext.Group group : position.board.groups) {
          allowed.addAll(group.stones());
          allowed.addAll(group.liberties());
        }
        for (TeacherBoardContext.Line line : position.board.lines) {
          allowed.addAll(line.moves());
          for (TeacherBoardContext.Group group :
              TeacherBoardContext.groups(
                  line.frames().get(line.frames().size() - 1).stones(),
                  position.board.width,
                  position.board.height)) {
            if (group.liberties().size() <= 2) allowed.addAll(group.liberties());
          }
        }
        for (TeacherEvidence.Candidate candidate : position.candidates)
          addCoordinate(allowed, candidate.coordinate);
        continue;
      }
      for (String move : position.playedContinuation) {
        addCoordinate(allowed, move);
      }
      for (TeacherEvidence.Candidate candidate : position.candidates) {
        addCoordinate(allowed, candidate.coordinate);
        if (candidate.variation != null) {
          for (String move : candidate.variation) {
            addCoordinate(allowed, move);
          }
        }
      }
    }
    return allowed;
  }

  private static void addCoordinate(Set<String> coordinates, String coordinate) {
    if (coordinate != null && !coordinate.isEmpty()) {
      coordinates.add(coordinate.toUpperCase(Locale.ROOT));
    }
  }
}
