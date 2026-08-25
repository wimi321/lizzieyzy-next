package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisCandidateValidatorTest {
  @Test
  void staleBatchContainingAnOccupiedCandidateIsRejected() {
    BoardData position = positionWithStone("D4");

    assertFalse(
        AnalysisCandidateValidator.allCandidatesOnEmptyPoints(
            List.of(candidate("Q16"), candidate("D4")), position));
  }

  @Test
  void rendererFilterRemovesOccupiedCandidateAndKeepsPass() {
    BoardData position = positionWithStone("D4");
    MoveData legal = candidate("Q16");
    MoveData pass = candidate("pass");

    List<MoveData> filtered =
        AnalysisCandidateValidator.withoutOccupiedCandidates(
            List.of(legal, candidate("D4"), pass), position);

    assertEquals(List.of(legal, pass), filtered);
  }

  @Test
  void currentBatchWithoutOccupiedCandidatesKeepsItsOriginalList() {
    BoardData position = positionWithStone("D4");
    List<MoveData> candidates = new ArrayList<>(List.of(candidate("Q16"), candidate("pass")));

    assertTrue(AnalysisCandidateValidator.allCandidatesOnEmptyPoints(candidates, position));
    assertSame(
        candidates,
        AnalysisCandidateValidator.withoutOccupiedCandidates(candidates, position));
  }

  private static BoardData positionWithStone(String coordinate) {
    BoardData position = BoardData.empty(Board.boardWidth, Board.boardHeight);
    int[] point = Board.asCoordinates(coordinate).orElseThrow();
    position.stones[Board.getIndex(point[0], point[1])] = Stone.BLACK;
    return position;
  }

  private static MoveData candidate(String coordinate) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.playouts = 10;
    return move;
  }
}
