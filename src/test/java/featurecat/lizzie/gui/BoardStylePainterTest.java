package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardStylePainterTest {
  @Test
  void chineseClassicStyleUsesFiveMarkersOnNineteenByNineteen() {
    List<Point> points = BoardStylePainter.chineseClassicMarkerPoints(19, 19);

    assertEquals(
        List.of(
            new Point(3, 3),
            new Point(15, 3),
            new Point(9, 9),
            new Point(3, 15),
            new Point(15, 15)),
        points);
  }

  @Test
  void chineseClassicStyleSkipsRectangularBoards() {
    assertTrue(BoardStylePainter.chineseClassicMarkerPoints(19, 13).isEmpty());
  }

  @Test
  void chineseClassicCoordinatesMirrorFromEachBoardEdge() {
    assertEquals("\u4e00", BoardStylePainter.chineseClassicCoordinateLabel(0, 19));
    assertEquals("\u4e5d", BoardStylePainter.chineseClassicCoordinateLabel(8, 19));
    assertEquals("\u5341", BoardStylePainter.chineseClassicCoordinateLabel(9, 19));
    assertEquals("\u4e5d", BoardStylePainter.chineseClassicCoordinateLabel(10, 19));
    assertEquals("\u4e00", BoardStylePainter.chineseClassicCoordinateLabel(18, 19));
  }
}
