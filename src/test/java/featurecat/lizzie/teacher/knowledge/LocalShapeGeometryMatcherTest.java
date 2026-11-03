package featurecat.lizzie.teacher.knowledge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalShapeGeometryMatcherTest {
  @Test
  void onlyDiagramsMarkedAsRealPositionsAreLookedForOnTheBoard() {
    LocalShapeGeometryMatcher.ProblemEntry problem = new LocalShapeGeometryMatcher.ProblemEntry();
    problem.initialStones =
        List.of(stone("B", "C3"), stone("W", "E3"), stone("B", "E5"), stone("W", "D2"));
    LocalShapeGeometryMatcher.TrainingMove answer = new LocalShapeGeometryMatcher.TrainingMove();
    answer.move = "F4";
    problem.correctMoves = List.of(answer);
    LocalShapeGeometryMatcher.KnowledgeMatchQuery query =
        new LocalShapeGeometryMatcher.KnowledgeMatchQuery();
    query.boardSnapshot = problem.initialStones;
    query.candidateMoves = List.of("F4");

    assertNull(LocalShapeGeometryMatcher.match(problem, query), "placeholder diagram");
    problem.positionVerified = true;
    assertNotNull(LocalShapeGeometryMatcher.match(problem, query), "real diagram");
  }

  private static LocalPatternMatcher.BoardSnapshotStone stone(String color, String point) {
    LocalPatternMatcher.BoardSnapshotStone stone = new LocalPatternMatcher.BoardSnapshotStone();
    stone.color = color;
    stone.point = point;
    return stone;
  }
}
