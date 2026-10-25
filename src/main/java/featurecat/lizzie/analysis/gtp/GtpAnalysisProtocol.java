package featurecat.lizzie.analysis.gtp;

import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure command planning and numbered response boundaries for remote GTP analysis. */
public final class GtpAnalysisProtocol {
  private static final Pattern ANALYZE = Pattern.compile("^kata-analyze ([BW]) ([0-9]+)(.*)$");
  private static final Pattern PLAY = Pattern.compile("play [BW] (?:[A-HJ-Z][1-9][0-9]?|pass)");

  private GtpAnalysisProtocol() {}

  public record Setup(ArrayDeque<String> commands, String analyze) {}

  public static Setup split(List<String> commands) {
    ArrayDeque<String> setup = new ArrayDeque<>();
    String analyze = null;
    for (String command : commands) {
      if (command.startsWith("kata-analyze ")) {
        if (analyze != null) return null;
        analyze = command;
      } else setup.addLast(command);
    }
    return analyze == null ? null : new Setup(setup, analyze);
  }

  public static boolean supportsOverviewBudget(int visits) {
    return visits > 0 && visits <= 500;
  }

  public static String overviewCommand(String command) {
    Matcher match = ANALYZE.matcher(command);
    if (!match.matches()) return command;
    try {
      int interval = Integer.parseInt(match.group(2));
      return interval > 10 ? "kata-analyze " + match.group(1) + " 10" + match.group(3) : command;
    } catch (NumberFormatException ex) {
      return command;
    }
  }

  /**
   * A play interrupts kata-analyze before execution; its numbered ACK also confirms the advance.
   */
  public static String advanceCommand(List<String> commands) {
    if (commands.size() < 2 || !ANALYZE.matcher(commands.get(commands.size() - 1)).matches())
      return null;
    for (int i = 0; i < commands.size() - 1; i++) {
      if (!PLAY.matcher(commands.get(i)).matches()) return null;
    }
    return commands.get(0);
  }

  public static boolean success(String line, int commandId) {
    return matches(line, commandId, "=");
  }

  public static boolean error(String line, int commandId) {
    return matches(line, commandId, "?");
  }

  private static boolean matches(String line, int commandId, String marker) {
    if (commandId <= 0 || line == null) return false;
    String prefix = marker + commandId;
    return line.equals(prefix) || line.startsWith(prefix + " ");
  }

  public static String payload(String line, int commandId) {
    String prefix = "=" + commandId;
    return line.length() <= prefix.length() ? "" : line.substring(prefix.length()).trim();
  }
}
