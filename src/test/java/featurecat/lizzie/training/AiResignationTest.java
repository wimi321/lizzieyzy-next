package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiResignationTest {
  private static final int AREA = 19 * 19;

  @Test
  void kyuOpponentsFightOnLikeKatagosFiveKyuConfig() {
    AiResignation kyu = AiResignation.forProfile("rank_5k");

    assertEquals(20, kyu.turnsNeeded());
    assertEquals(40.0, kyu.minPointsBehind(), 1e-12);
    assertEquals(0.005, kyu.maxWinrate(), 1e-12);
    assertEquals(kyu.turnsNeeded(), AiResignation.forProfile("rank_20k").turnsNeeded());
  }

  @Test
  void strongerOpponentsResignSoonerUpToKatagosNineDanConfig() {
    AiResignation nineDan = AiResignation.forProfile("rank_9d");

    assertEquals(10, nineDan.turnsNeeded());
    assertEquals(20.0, nineDan.minPointsBehind(), 1e-12);
    assertEquals(0.01, nineDan.maxWinrate(), 1e-12);
    int previous = Integer.MAX_VALUE;
    for (int dan = 1; dan <= 9; dan++) {
      int turns = AiResignation.forProfile("rank_" + dan + "d").turnsNeeded();
      assertTrue(turns <= previous, dan + " dan");
      previous = turns;
    }
  }

  @Test
  void aLevelResignsLikeTheConfigItWasTunedWith() {
    AiResignation kyu = AiResignation.forPlay(CoachLevel.KYU_1.play());
    assertEquals(20, kyu.turnsNeeded());
    assertEquals(40.0, kyu.minPointsBehind(), 1e-12);
    assertEquals(0.005, kyu.maxWinrate(), 1e-12);

    // Every search level, from 1 dan up, used resignThreshold -0.98 for 10 turns and 20 points.
    for (CoachLevel level : new CoachLevel[] {CoachLevel.DAN_1, CoachLevel.DAN_11}) {
      AiResignation search = AiResignation.forPlay(level.play());
      assertEquals(10, search.turnsNeeded(), level.name());
      assertEquals(20.0, search.minPointsBehind(), 1e-12);
      assertEquals(0.01, search.maxWinrate(), 1e-12);
    }
  }

  @Test
  void resignsOnlyAfterEnoughHopelessTurnsInARow() {
    AiResignation nineDan = AiResignation.forProfile("rank_9d");

    for (int turn = 1; turn < 10; turn++) {
      assertFalse(nineDan.shouldResign(0.001, -30.0, 200 + turn, AREA), "turn " + turn);
    }
    assertTrue(nineDan.shouldResign(0.001, -30.0, 210, AREA));
  }

  @Test
  void aTurnWithChancesStartsTheCountAgain() {
    AiResignation nineDan = AiResignation.forProfile("rank_9d");
    for (int turn = 0; turn < 9; turn++) {
      nineDan.shouldResign(0.001, -30.0, 200 + turn, AREA);
    }

    assertFalse(nineDan.shouldResign(0.2, -5.0, 210, AREA));
    assertFalse(nineDan.shouldResign(0.001, -30.0, 211, AREA));
  }

  @Test
  void neverBeforeFortyPercentOfTheBoardOrWhenOnlySlightlyBehind() {
    AiResignation eager = AiResignation.of(0.01, 20.0, 1, AiResignation.MIN_BOARD_SHARE);

    assertFalse(eager.shouldResign(0.0, -80.0, 140, AREA));
    assertFalse(eager.shouldResign(0.0, -10.0, 200, AREA));
    assertFalse(eager.shouldResign(Double.NaN, -80.0, 200, AREA));
    assertTrue(eager.shouldResign(0.0, -80.0, 145, AREA));
  }
}
