package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import featurecat.lizzie.rules.Stone;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnershipEstimateTest {

  @Test
  void calculatesTerritoryAndLivingStonesFromBlackOwnership() {
    Stone[] stones = emptyBoard(3, 3);
    stones[4] = Stone.BLACK;

    OwnershipEstimate.Result result =
        OwnershipEstimate.calculate(
            3, 3, stones, 2, 1, Collections.nCopies(9, 0.9), 0.4);

    assertEquals(8, result.blackPoints());
    assertEquals(0, result.whitePoints());
    assertEquals(1, result.blackAlive());
    assertEquals(0.0, result.renderedOwnership().get(4));
    assertEquals(2, result.blackCaptures());
    assertEquals(1, result.whiteCaptures());
  }

  @Test
  void countsStonesOwnedByTheOpponentAsPrisoners() {
    Stone[] stones = emptyBoard(3, 3);
    stones[4] = Stone.BLACK;

    OwnershipEstimate.Result result =
        OwnershipEstimate.calculate(
            3, 3, stones, 0, 0, Collections.nCopies(9, -0.8), 0.4);

    assertEquals(1, result.blackPrisoners());
    assertEquals(9, result.whitePoints());
    assertEquals(0, result.blackAlive());
  }

  @Test
  void appliesThresholdBeforeRemovingUnsurroundedTerritory() {
    Stone[] stones = emptyBoard(3, 3);
    List<Double> ownership = Arrays.asList(0.9, 0.9, 0.9, 0.9, 0.9, 0.2, 0.9, 0.9, 0.9);

    OwnershipEstimate.Result result =
        OwnershipEstimate.calculate(3, 3, stones, 0, 0, ownership, 0.4);

    assertEquals(5, result.blackPoints());
    assertEquals(0.0, result.renderedOwnership().get(4));
    assertEquals(0.0, result.renderedOwnership().get(5));
  }

  @Test
  void rejectsOwnershipForAnotherBoardSize() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OwnershipEstimate.calculate(
                3, 3, emptyBoard(3, 3), 0, 0, Collections.nCopies(8, 1.0), 0.4));
  }

  @Test
  void treatsAnInvalidThresholdAsZero() {
    OwnershipEstimate.Result result =
        OwnershipEstimate.calculate(
            1, 1, emptyBoard(1, 1), 0, 0, List.of(0.7), Double.NaN);

    assertEquals(1, result.blackPoints());
  }

  private static Stone[] emptyBoard(int width, int height) {
    Stone[] stones = new Stone[width * height];
    Arrays.fill(stones, Stone.EMPTY);
    return stones;
  }
}
