package featurecat.lizzie.training;

import java.util.List;
import java.util.Optional;

/**
 * A community table that lines KataGo's human-style ranks up with Fox / Tygem ranks and real-world
 * (Chinese amateur) grades, strongest first. It is a rough guide for picking an opponent, not a
 * rating.
 */
public final class RankReference {
  /** What the real-world column says; the two amateur kinds carry a number. */
  public enum Grade {
    BEYOND_TOP_PRO,
    TOP_PRO,
    PRO,
    TOP_AMATEUR,
    AMATEUR_DAN,
    AMATEUR_KYU
  }

  /**
   * One row of the table. {@code strong} is the 强 half step above {@code rank}; 10 and 11 dan are
   * above the coach's 9 dan, so only the plain 1 dan to 9 dan and 1 to 10 kyu rows can be played.
   */
  public record Row(
      int rank, boolean dan, boolean strong, String fox, Grade grade, int gradeNumber) {
    public boolean sameGrade(Row other) {
      return grade == other.grade && gradeNumber == other.gradeNumber;
    }
  }

  public static final List<Row> ROWS =
      List.of(
          dan(11, false, "10D", Grade.BEYOND_TOP_PRO, 0),
          dan(10, true, "10D", Grade.TOP_PRO, 0),
          dan(10, false, "9D", Grade.PRO, 0),
          dan(9, true, "9D", Grade.TOP_AMATEUR, 0),
          dan(9, false, "9D", Grade.AMATEUR_DAN, 6),
          dan(8, true, "8-9D", Grade.AMATEUR_DAN, 5),
          dan(8, false, "8D", Grade.AMATEUR_DAN, 5),
          dan(7, true, "7-8D", Grade.AMATEUR_DAN, 5),
          dan(7, false, "7D", Grade.AMATEUR_DAN, 5),
          dan(6, true, "6-7D", Grade.AMATEUR_DAN, 5),
          dan(6, false, "6D", Grade.AMATEUR_DAN, 5),
          dan(5, true, "5-6D", Grade.AMATEUR_DAN, 5),
          dan(5, false, "5D", Grade.AMATEUR_DAN, 4),
          dan(4, true, "4-5D", Grade.AMATEUR_DAN, 4),
          dan(4, false, "4D", Grade.AMATEUR_DAN, 4),
          dan(3, true, "3-4D", Grade.AMATEUR_DAN, 3),
          dan(3, false, "3D", Grade.AMATEUR_DAN, 3),
          dan(2, true, "2-3D", Grade.AMATEUR_DAN, 3),
          dan(2, false, "2D", Grade.AMATEUR_DAN, 2),
          dan(1, true, "1-2D", Grade.AMATEUR_DAN, 2),
          dan(1, false, "1D", Grade.AMATEUR_DAN, 1),
          kyu(1, "1K"),
          kyu(2, "2K"),
          kyu(3, "3-4K"),
          kyu(4, "5-6K"),
          kyu(5, "7-8K"),
          kyu(6, "9-10K"),
          kyu(7, "11-12K"),
          kyu(8, "13-14K"),
          kyu(9, "15-16K"),
          kyu(10, "17-18K"));

  private RankReference() {}

  /** The row for an opponent the coach can play, or empty below 10 kyu where the table stops. */
  public static Optional<Row> forCoachRank(int rank, boolean dan) {
    return ROWS.stream()
        .filter(row -> !row.strong && row.dan == dan && row.rank == rank)
        .findFirst();
  }

  private static Row dan(int rank, boolean strong, String fox, Grade grade, int gradeNumber) {
    return new Row(rank, true, strong, fox, grade, gradeNumber);
  }

  private static Row kyu(int rank, String fox) {
    return new Row(rank, false, false, fox, Grade.AMATEUR_KYU, rank);
  }
}
