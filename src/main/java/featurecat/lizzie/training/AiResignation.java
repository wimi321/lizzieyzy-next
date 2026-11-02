package featurecat.lizzie.training;

import java.util.Locale;

/**
 * When the AI gives up, by its rank, after KataGo's human-imitation configs: weaker players fight
 * much longer. gtp_human5k_example.cfg resigns only after 20 turns in a row below 0.5% and at least
 * 40 points behind; gtp_human9d_search_example.cfg after 10 turns below 1% and 20 points behind.
 * Both wait until 40% of the board's points have been played. Dan ranks in between are
 * interpolated; kyu ranks use the 5 kyu values.
 */
public final class AiResignation {
  static final double MIN_BOARD_SHARE = 0.4;

  private final double maxWinrate;
  private final double minPointsBehind;
  private final int turnsNeeded;
  private final double minBoardShare;
  private int hopelessTurns;

  private AiResignation(
      double maxWinrate, double minPointsBehind, int turnsNeeded, double minBoardShare) {
    this.maxWinrate = maxWinrate;
    this.minPointsBehind = minPointsBehind;
    this.turnsNeeded = Math.max(1, turnsNeeded);
    this.minBoardShare = minBoardShare;
  }

  public static AiResignation forProfile(String profile) {
    double strength = danStrength(profile);
    return of(
        0.005 + 0.005 * strength,
        40.0 - 20.0 * strength,
        (int) Math.round(20.0 - 10.0 * strength),
        MIN_BOARD_SHARE);
  }

  /**
   * The resign settings of the level's config: a policy level fights on like
   * gtp_human5k_example.cfg, a search level gives up like gtp_human9d_search_example.cfg.
   */
  public static AiResignation forPlay(HumanSlPlaySettings play) {
    return play.searches()
        ? of(0.01, 20.0, 10, MIN_BOARD_SHARE)
        : of(0.005, 40.0, 20, MIN_BOARD_SHARE);
  }

  /** Explicit thresholds; games use {@link #forPlay}, or {@link #forProfile} for a bare profile. */
  public static AiResignation of(
      double maxWinrate, double minPointsBehind, int turnsNeeded, double minBoardShare) {
    return new AiResignation(maxWinrate, minPointsBehind, turnsNeeded, minBoardShare);
  }

  /** 0 for kyu ranks and 1 dan, 1 for 9 dan and stronger profiles. */
  private static double danStrength(String profile) {
    if (profile == null) {
      return 1.0;
    }
    String normalized = profile.trim().toLowerCase(Locale.ROOT);
    if (normalized.matches("rank_\\d+k")) {
      return 0.0;
    }
    if (normalized.matches("rank_\\d+d")) {
      int dan = Integer.parseInt(normalized.substring(5, normalized.length() - 1));
      return Math.max(0.0, Math.min(1.0, (dan - 1) / 8.0));
    }
    return 1.0;
  }

  /**
   * Records the AI's own view of its position on its turn and says whether it should resign now. A
   * turn without an evaluation leaves the count unchanged.
   */
  public boolean shouldResign(double aiWinrate, double aiScoreLead, int moveNumber, int boardArea) {
    if (!Double.isFinite(aiWinrate) || !Double.isFinite(aiScoreLead)) {
      return false;
    }
    boolean hopeless =
        moveNumber >= minBoardShare * boardArea
            && aiWinrate <= maxWinrate
            && aiScoreLead <= -minPointsBehind;
    hopelessTurns = hopeless ? hopelessTurns + 1 : 0;
    return hopelessTurns >= turnsNeeded;
  }

  int turnsNeeded() {
    return turnsNeeded;
  }

  double minPointsBehind() {
    return minPointsBehind;
  }

  double maxWinrate() {
    return maxWinrate;
  }
}
