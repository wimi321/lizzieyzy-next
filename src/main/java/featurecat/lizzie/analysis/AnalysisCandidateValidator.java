package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Guards candidate overlays against analysis output that belongs to an older board position. */
public final class AnalysisCandidateValidator {
  private AnalysisCandidateValidator() {}

  public static boolean isEmptyPoint(BoardData position, int[] coordinates) {
    if (position == null
        || position.stones == null
        || coordinates == null
        || coordinates.length < 2
        || coordinates[0] < 0
        || coordinates[0] >= Board.boardWidth
        || coordinates[1] < 0
        || coordinates[1] >= Board.boardHeight) {
      return false;
    }
    int index = Board.getIndex(coordinates[0], coordinates[1]);
    return index >= 0
        && index < position.stones.length
        && position.stones[index] == Stone.EMPTY;
  }

  public static boolean allCandidatesOnEmptyPoints(
      List<MoveData> candidates, BoardData position) {
    if (candidates == null || candidates.isEmpty()) {
      return true;
    }
    for (MoveData candidate : candidates) {
      Optional<int[]> coordinates = candidateCoordinates(candidate);
      if (coordinates.isPresent() && !isEmptyPoint(position, coordinates.get())) {
        return false;
      }
    }
    return true;
  }

  /** Removes only board-coordinate candidates that are occupied; pass-like moves remain intact. */
  public static List<MoveData> withoutOccupiedCandidates(
      List<MoveData> candidates, BoardData position) {
    if (candidates == null || candidates.isEmpty()) {
      return candidates;
    }
    ArrayList<MoveData> filtered = null;
    for (int index = 0; index < candidates.size(); index++) {
      MoveData candidate = candidates.get(index);
      Optional<int[]> coordinates = candidateCoordinates(candidate);
      boolean occupied = coordinates.isPresent() && !isEmptyPoint(position, coordinates.get());
      if (occupied) {
        if (filtered == null) {
          filtered = new ArrayList<>(candidates.subList(0, index));
        }
      } else if (filtered != null) {
        filtered.add(candidate);
      }
    }
    return filtered == null ? candidates : filtered;
  }

  private static Optional<int[]> candidateCoordinates(MoveData candidate) {
    return candidate == null || candidate.coordinate == null
        ? Optional.empty()
        : Board.asCoordinates(candidate.coordinate);
  }
}
