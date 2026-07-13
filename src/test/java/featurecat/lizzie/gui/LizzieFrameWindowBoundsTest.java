package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LizzieFrameWindowBoundsTest {
  @Test
  void clampsPersistedPositionAfterScaleIncrease() {
    Rectangle fitted =
        LizzieFrame.fitWindowBounds(
            new Rectangle(321, 159, 1065, 700),
            Collections.singletonList(new Rectangle(0, 0, 1280, 760)));

    assertEquals(new Rectangle(215, 60, 1065, 700), fitted);
  }

  @Test
  void shrinksOversizedWindowToCurrentWorkArea() {
    Rectangle fitted =
        LizzieFrame.fitWindowBounds(
            new Rectangle(-200, -100, 1800, 1000),
            Collections.singletonList(new Rectangle(0, 0, 1280, 760)));

    assertEquals(new Rectangle(0, 0, 1280, 760), fitted);
  }

  @Test
  void preservesBoundsThatAlreadyFit() {
    Rectangle requested = new Rectangle(40, 30, 1000, 700);

    assertEquals(
        requested,
        LizzieFrame.fitWindowBounds(
            requested, Collections.singletonList(new Rectangle(0, 0, 1280, 760))));
  }

  @Test
  void choosesMonitorWithLargestIntersection() {
    Rectangle fitted =
        LizzieFrame.fitWindowBounds(
            new Rectangle(-1700, 100, 1200, 700),
            Arrays.asList(
                new Rectangle(0, 0, 1280, 760), new Rectangle(-1920, 0, 1920, 1040)));

    assertEquals(new Rectangle(-1700, 100, 1200, 700), fitted);
  }
}
