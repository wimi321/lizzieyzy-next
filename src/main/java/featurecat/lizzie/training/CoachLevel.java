package featurecat.lizzie.training;

import static featurecat.lizzie.training.HumanSlPlaySettings.policy;
import static featurecat.lizzie.training.HumanSlPlaySettings.search;

/**
 * The opponent strengths of AI 陪练, weakest first: the rows of the community table that lines
 * KataGo's human-style levels up with Fox / Tygem ranks and real-world (Chinese amateur) grades,
 * each with the KataGo settings that play it. Up to 1 kyu the AI plays the raw HumanSL policy of a
 * pre-AlphaZero profile; from 1 dan it searches, and each 强 half step sits between two dan levels.
 * The settings are those of the gtp_human*_example.cfg configs the table was measured with.
 */
public enum CoachLevel {
  KYU_10(Kind.KYU, 10, "17-18K", Grade.AMATEUR_KYU, 10, policy("preaz_20k")),
  KYU_9(Kind.KYU, 9, "15-16K", Grade.AMATEUR_KYU, 9, policy("preaz_18k")),
  KYU_8(Kind.KYU, 8, "13-14K", Grade.AMATEUR_KYU, 8, policy("preaz_16k")),
  KYU_7(Kind.KYU, 7, "11-12K", Grade.AMATEUR_KYU, 7, policy("preaz_14k")),
  KYU_6(Kind.KYU, 6, "9-10K", Grade.AMATEUR_KYU, 6, policy("preaz_12k")),
  KYU_5(Kind.KYU, 5, "7-8K", Grade.AMATEUR_KYU, 5, policy("preaz_11k")),
  KYU_4(Kind.KYU, 4, "5-6K", Grade.AMATEUR_KYU, 4, policy("preaz_10k")),
  KYU_3(Kind.KYU, 3, "3-4K", Grade.AMATEUR_KYU, 3, policy("preaz_9k")),
  KYU_2(Kind.KYU, 2, "2K", Grade.AMATEUR_KYU, 2, policy("preaz_8k")),
  KYU_1(Kind.KYU, 1, "1K", Grade.AMATEUR_KYU, 1, policy("preaz_7k")),
  DAN_1(Kind.DAN, 1, "1D", Grade.AMATEUR_DAN, 1, search("rank_5k")),
  STRONG_DAN_1(Kind.STRONG_DAN, 1, "1-2D", Grade.AMATEUR_DAN, 2, halfStep("rank_4k")),
  DAN_2(Kind.DAN, 2, "2D", Grade.AMATEUR_DAN, 2, search("rank_4k")),
  STRONG_DAN_2(Kind.STRONG_DAN, 2, "2-3D", Grade.AMATEUR_DAN, 3, halfStep("rank_3k")),
  DAN_3(Kind.DAN, 3, "3D", Grade.AMATEUR_DAN, 3, search("rank_3k")),
  STRONG_DAN_3(Kind.STRONG_DAN, 3, "3-4D", Grade.AMATEUR_DAN, 3, halfStep("rank_2k")),
  DAN_4(Kind.DAN, 4, "4D", Grade.AMATEUR_DAN, 4, search("rank_2k")),
  STRONG_DAN_4(Kind.STRONG_DAN, 4, "4-5D", Grade.AMATEUR_DAN, 4, halfStep("rank_1k")),
  DAN_5(Kind.DAN, 5, "5D", Grade.AMATEUR_DAN, 4, search("rank_1k")),
  STRONG_DAN_5(Kind.STRONG_DAN, 5, "5-6D", Grade.AMATEUR_DAN, 5, halfStep("rank_1d")),
  DAN_6(Kind.DAN, 6, "6D", Grade.AMATEUR_DAN, 5, search("rank_1d").pikl(1.25)),
  STRONG_DAN_6(
      Kind.STRONG_DAN,
      6,
      "6-7D",
      Grade.AMATEUR_DAN,
      5,
      search("rank_2d").visits(80).pikl(1.0).rootWeightless(0.2)),
  DAN_7(
      Kind.DAN,
      7,
      "7D",
      Grade.AMATEUR_DAN,
      5,
      search("rank_3d").visits(80).pikl(1.25).rootWeightless(0.2)),
  STRONG_DAN_7(
      Kind.STRONG_DAN,
      7,
      "7-8D",
      Grade.AMATEUR_DAN,
      5,
      search("rank_3d").visits(80).pikl(1.0).rootWeightless(0.5)),
  DAN_8(
      Kind.DAN,
      8,
      "8D",
      Grade.AMATEUR_DAN,
      5,
      search("rank_3d").visits(80).pikl(0.9).scoreUtility(1.0)),
  STRONG_DAN_8(
      Kind.STRONG_DAN,
      8,
      "8-9D",
      Grade.AMATEUR_DAN,
      5,
      search("rank_4d").visits(100).pikl(1.25).rootWeightless(0.2).scoreUtility(1.0)),
  DAN_9(
      Kind.DAN,
      9,
      "9D",
      Grade.AMATEUR_DAN,
      6,
      search("rank_4d").visits(100).pikl(0.95).rootWeightless(0.9).scoreUtility(1.0)),
  STRONG_DAN_9(
      Kind.STRONG_DAN,
      9,
      "9D",
      Grade.TOP_AMATEUR,
      0,
      search("rank_6d").visits(100).pikl(0.95).rootWeightless(0.4)),
  DAN_10(
      Kind.DAN,
      10,
      "9D",
      Grade.PRO,
      0,
      search("rank_7d").visits(100).pikl(0.8).rootWeightless(0.3).permanentExploration(2.0)),
  STRONG_DAN_10(
      Kind.STRONG_DAN,
      10,
      "10D",
      Grade.TOP_PRO,
      0,
      search("rank_8d").visits(200).pikl(0.25).rootWeightless(0.9).moveTime(2)),
  DAN_11(
      Kind.DAN,
      11,
      "10D",
      Grade.BEYOND_TOP_PRO,
      0,
      search("rank_9d").visits(400).pikl(0.01).rootWeightless(1.0).moveTime(2));

  /** The level a new game starts at. */
  public static final CoachLevel DEFAULT = DAN_3;

  /** A plain level, or the 强 half step above a dan level. */
  public enum Kind {
    KYU,
    DAN,
    STRONG_DAN
  }

  /** What the real-world column says; the two amateur kinds carry a number. */
  public enum Grade {
    BEYOND_TOP_PRO,
    TOP_PRO,
    PRO,
    TOP_AMATEUR,
    AMATEUR_DAN,
    AMATEUR_KYU
  }

  private final Kind kind;
  private final int rank;
  private final String fox;
  private final Grade grade;
  private final int gradeNumber;
  private final HumanSlPlaySettings play;

  CoachLevel(
      Kind kind, int rank, String fox, Grade grade, int gradeNumber, HumanSlPlaySettings play) {
    this.kind = kind;
    this.rank = rank;
    this.fox = fox;
    this.grade = grade;
    this.gradeNumber = gradeNumber;
    this.play = play;
  }

  /** The 强 configs: a rank_ profile one step up, explored less, with human odds cut later. */
  private static HumanSlPlaySettings halfStep(String profile) {
    return search(profile).pikl(1.25).rootWeightless(0.1).onlyBelowProb(0.01);
  }

  public Kind kind() {
    return kind;
  }

  /** The number of the KataGo level: 3 for 3 dan and for 强3段. */
  public int rank() {
    return rank;
  }

  public boolean dan() {
    return kind != Kind.KYU;
  }

  public boolean strong() {
    return kind == Kind.STRONG_DAN;
  }

  /** The Fox / Tygem rank, such as {@code 3D}, {@code 8-9D} or {@code 17-18K}. */
  public String fox() {
    return fox;
  }

  public Grade grade() {
    return grade;
  }

  /** The number of an amateur grade, 0 for the professional ones. */
  public int gradeNumber() {
    return gradeNumber;
  }

  public HumanSlPlaySettings play() {
    return play;
  }

  /** The level at {@code index} on the weakest-first scale, clamped to it. */
  public static CoachLevel at(int index) {
    CoachLevel[] levels = values();
    return levels[Math.max(0, Math.min(levels.length - 1, index))];
  }

  /**
   * The level an older setting named by its number: the plain level of that number. Kyu ranks
   * weaker than the table's 10 kyu become 10 kyu, which already plays the weakest HumanSL profile.
   */
  public static CoachLevel forRank(int rank, boolean dan) {
    Kind kind = dan ? Kind.DAN : Kind.KYU;
    int bounded = dan ? Math.max(1, Math.min(11, rank)) : Math.max(1, Math.min(10, rank));
    for (CoachLevel level : values()) {
      if (level.kind == kind && level.rank == bounded) {
        return level;
      }
    }
    return DEFAULT;
  }

  /** The level saved under {@code name}, or {@code fallback} when there is none by that name. */
  public static CoachLevel named(String name, CoachLevel fallback) {
    if (name != null) {
      for (CoachLevel level : values()) {
        if (level.name().equals(name.trim())) {
          return level;
        }
      }
    }
    return fallback;
  }
}
