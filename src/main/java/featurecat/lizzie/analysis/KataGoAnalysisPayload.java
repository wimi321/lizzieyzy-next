package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import java.util.ArrayList;
import java.util.List;

/** One GTP analysis frame, before candidate display limits or cache admission. */
public final class KataGoAnalysisPayload {
  public final List<MoveData> moves;
  public final int rootVisits;
  public final double rootWinrate;
  public final double rootScoreMean;
  public final List<Double> ownership;

  private KataGoAnalysisPayload(List<MoveData> moves, int rootVisits,
      double rootWinrate, double rootScoreMean, List<Double> ownership) {
    this.moves = moves;
    this.rootVisits = rootVisits;
    this.rootWinrate = rootWinrate;
    this.rootScoreMean = rootScoreMean;
    this.ownership = ownership;
  }

  public int totalVisits() {
    return rootVisits >= 0 ? rootVisits : MoveData.getPlayouts(moves);
  }

  public static KataGoAnalysisPayload parse(String line) {
    MoveData.TokenCursor tokens = new MoveData.TokenCursor(line);
    List<MoveData> moves = new ArrayList<>();
    int rootVisits = -1;
    double rootWinrate = Double.NaN;
    double rootScoreMean = Double.NaN;
    List<Double> ownership = null;
    while (tokens.next()) {
      if (tokens.matches("info")) continue;
      if (tokens.matches("move")) {
        tokens.rewind();
        MoveData move = MoveData.fromInfoKatago(tokens);
        moves.add(move);
      } else if (tokens.matches("rootInfo")) {
        while (tokens.next()) {
          if (tokens.isTopLevelBoundary()) {
            tokens.rewind();
            break;
          }
          int keyStart = tokens.start;
          int keyEnd = tokens.end;
          if (!tokens.next()) break;
          if (tokens.isTopLevelBoundary()) {
            tokens.rewind();
            break;
          }
          if (tokens.matches(tokens.value, keyStart, keyEnd, "visits")) {
            rootVisits = tokens.fastInt();
          } else if (tokens.matches(tokens.value, keyStart, keyEnd, "winrate")) {
            rootWinrate = tokens.fastDouble() * 100.0;
          } else if (tokens.matches(tokens.value, keyStart, keyEnd, "scoreLead")
              || tokens.matches(tokens.value, keyStart, keyEnd, "scoreMean")) {
            rootScoreMean = tokens.fastDouble();
          }
        }
      } else if (tokens.matches("ownership") || tokens.matches("ownershipStdev")) {
        boolean retain = tokens.matches("ownership");
        List<Double> values = retain ? new ArrayList<>() : null;
        while (tokens.next()) {
          if (tokens.isTopLevelBoundary()) {
            tokens.rewind();
            break;
          }
          if (retain) values.add(tokens.fastDouble());
        }
        if (retain) ownership = BoardData.compactEstimateArray(values);
      }
    }
    return new KataGoAnalysisPayload(moves, rootVisits, rootWinrate, rootScoreMean, ownership);
  }
}
