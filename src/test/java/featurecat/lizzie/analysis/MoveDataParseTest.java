package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the KataGo info-line parsing and the memory shape of its results: variation/pvVisits must
 * be compact ArrayLists, not subList views that pin the whole tokenized line (hundreds of tokens
 * with ownership data) for as long as the MoveData lives in the game tree.
 */
class MoveDataParseTest {
  private static Config previousConfig;

  @BeforeAll
  static void installMinimalConfig() throws Exception {
    previousConfig = Lizzie.config;
    Lizzie.config = allocate(Config.class); // limitBranchLength defaults to 0 (unlimited)
  }

  @AfterAll
  static void restoreConfig() {
    Lizzie.config = previousConfig;
  }

  @Test
  void parsesKataGoInfoLine() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move Q16 visits 100 winrate 0.5123 scoreMean 1.5 prior 0.08 order 0"
                + " pv Q16 D4 Q4 pvVisits 60 25 15");
    assertEquals("Q16", move.coordinate);
    assertEquals(100, move.playouts);
    assertEquals(51.23, move.winrate, 1e-9);
    assertEquals(1.5, move.scoreMean, 1e-9);
    assertEquals(0, move.order);
    assertEquals(List.of("Q16", "D4", "Q4"), move.variation);
    assertEquals(List.of("60", "25", "15"), move.pvVisits);
    assertNull(move.movesEstimateArray);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
    assertInstanceOf(ArrayList.class, move.pvVisits, "pvVisits must not be a subList view");
  }

  @Test
  void movesOwnershipStopsTheVariation() {
    MoveData move =
        MoveData.fromInfoKatago(
            "move D4 visits 10 winrate 0.6 pv D4 Q16 pvVisits 6 4 movesOwnership 0.5 -0.5");
    assertEquals(List.of("D4", "Q16"), move.variation);
    assertEquals(List.of("6", "4"), move.pvVisits);
    assertEquals(List.of(0.5, -0.5), move.movesEstimateArray);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
  }

  @Test
  void branchLimitKeepsVariationAndPvVisitsCompact() {
    Lizzie.config.limitBranchLength = 2;
    try {
      MoveData move =
          MoveData.fromInfoKatago(
              "move Q16 visits 100 winrate 0.5123 pv Q16 D4 Q4 C3 pvVisits 40 30 20 10");

      assertEquals(List.of("Q16", "D4"), move.variation);
      assertEquals(List.of("40", "30"), move.pvVisits);
      assertInstanceOf(ArrayList.class, move.variation, "variation must be a compact ArrayList");
      assertInstanceOf(ArrayList.class, move.pvVisits, "pvVisits must be a compact ArrayList");
    } finally {
      Lizzie.config.limitBranchLength = 0;
    }
  }

  @Test
  void fromInfoParsesLeelaZeroLine() {
    MoveData move =
        MoveData.fromInfo("move Q16 visits 50 winrate 5123 prior 800 order 0 pv Q16 D4");
    assertEquals("Q16", move.coordinate);
    assertEquals(50, move.playouts);
    assertEquals(List.of("Q16", "D4"), move.variation);
    assertInstanceOf(ArrayList.class, move.variation, "variation must not be a subList view");
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
    return type.cast(unsafe.allocateInstance(type));
  }
}
