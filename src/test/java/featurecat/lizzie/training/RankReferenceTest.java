package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RankReferenceTest {
  @Test
  void theTableRunsFromElevenDanDownToTenKyu() {
    assertEquals(31, RankReference.ROWS.size());
    RankReference.Row top = RankReference.ROWS.get(0);
    assertEquals(11, top.rank());
    assertEquals(RankReference.Grade.BEYOND_TOP_PRO, top.grade());
    RankReference.Row bottom = RankReference.ROWS.get(RankReference.ROWS.size() - 1);
    assertEquals(10, bottom.rank());
    assertFalse(bottom.dan());
    assertEquals("17-18K", bottom.fox());
  }

  @Test
  void everyDanAndTheFirstTenKyuOfTheCoachHaveARow() {
    for (int rank = 1; rank <= 9; rank++) {
      assertTrue(RankReference.forCoachRank(rank, true).isPresent(), rank + " dan");
    }
    for (int rank = 1; rank <= 10; rank++) {
      assertTrue(RankReference.forCoachRank(rank, false).isPresent(), rank + " kyu");
    }
    assertFalse(RankReference.forCoachRank(11, false).isPresent());
    assertFalse(RankReference.forCoachRank(20, false).isPresent());
  }

  @Test
  void theCoachRankUsesThePlainRowNotTheStrongOne() {
    RankReference.Row threeDan = RankReference.forCoachRank(3, true).orElseThrow();
    assertFalse(threeDan.strong());
    assertEquals("3D", threeDan.fox());
    assertEquals(RankReference.Grade.AMATEUR_DAN, threeDan.grade());
    assertEquals(3, threeDan.gradeNumber());

    RankReference.Row nineDan = RankReference.forCoachRank(9, true).orElseThrow();
    assertEquals("9D", nineDan.fox());
    assertEquals(6, nineDan.gradeNumber());

    RankReference.Row threeKyu = RankReference.forCoachRank(3, false).orElseThrow();
    assertEquals("3-4K", threeKyu.fox());
    assertEquals(RankReference.Grade.AMATEUR_KYU, threeKyu.grade());
  }
}
