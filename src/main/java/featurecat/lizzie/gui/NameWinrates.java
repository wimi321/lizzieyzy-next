package featurecat.lizzie.gui;

import org.json.JSONObject;

/**
 * Live win rates beside the players' names under the board. They stay hidden until a name is
 * clicked, a second click hides them again, and the choice is kept in the UI settings.
 */
final class NameWinrates {
  static final String SHOWN_KEY = "show-name-winrates";

  /** The widest win rate as drawn beside a name, used to leave room for it. */
  static final String WIDEST = "  100.0%";

  private static final String UNKNOWN = "--";

  private NameWinrates() {}

  static boolean shown(JSONObject settings) {
    return settings != null && settings.optBoolean(SHOWN_KEY, false);
  }

  static void toggle(JSONObject settings) {
    if (settings != null) settings.put(SHOWN_KEY, !shown(settings));
  }

  /** Black's name followed by Black's win rate. */
  static String black(String name, WinrateReading reading) {
    return name + "  " + (reading.known() ? percent(tenths(reading)) : UNKNOWN);
  }

  /** White's win rate, which makes 100 with Black's as shown, followed by White's name. */
  static String white(String name, WinrateReading reading) {
    return (reading.known() ? percent(1000 - tenths(reading)) : UNKNOWN) + "  " + name;
  }

  private static long tenths(WinrateReading reading) {
    return Math.round(reading.black() * 10);
  }

  private static String percent(long tenths) {
    return tenths / 10 + "." + tenths % 10 + "%";
  }
}
