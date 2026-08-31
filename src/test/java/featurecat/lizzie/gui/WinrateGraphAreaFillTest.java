package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.List;
import org.junit.jupiter.api.Test;

class WinrateGraphAreaFillTest {
  private static final double EPSILON = 1e-9;

  @Test
  void sameSideSegmentAboveBaselineFillsATrapezoidToTheMidline() {
    List<WinrateGraph.BaselineFillShape> shapes =
        WinrateGraph.baselineFillShapes(10, 20, 50, 30, 80);

    assertEquals(1, shapes.size());
    WinrateGraph.BaselineFillShape shape = shapes.get(0);
    assertTrue(shape.aboveBaseline);
    assertVertices(
        shape.vertices,
        point(10, 20),
        point(50, 30),
        point(50, 80),
        point(10, 80));
    assertVerticesStayOnOrAboveBaseline(shape, 80);
  }

  @Test
  void sameSideSegmentBelowBaselineFillsATrapezoidToTheMidline() {
    List<WinrateGraph.BaselineFillShape> shapes =
        WinrateGraph.baselineFillShapes(12, 90, 48, 110, 60);

    assertEquals(1, shapes.size());
    WinrateGraph.BaselineFillShape shape = shapes.get(0);
    assertFalse(shape.aboveBaseline);
    assertVertices(
        shape.vertices,
        point(12, 90),
        point(48, 110),
        point(48, 60),
        point(12, 60));
    assertVerticesStayOnOrBelowBaseline(shape, 60);
  }

  @Test
  void crossingSegmentSplitsAtTheInterpolatedBaselineIntersection() {
    List<WinrateGraph.BaselineFillShape> shapes =
        WinrateGraph.baselineFillShapes(0, 10, 80, 90, 50);

    assertEquals(2, shapes.size());
    WinrateGraph.BaselineFillShape above = shapes.get(0);
    WinrateGraph.BaselineFillShape below = shapes.get(1);
    assertTrue(above.aboveBaseline);
    assertFalse(below.aboveBaseline);
    assertVertices(above.vertices, point(0, 10), point(40, 50), point(0, 50));
    assertVertices(below.vertices, point(80, 90), point(40, 50), point(80, 50));
    assertVerticesStayOnOrAboveBaseline(above, 50);
    assertVerticesStayOnOrBelowBaseline(below, 50);
  }

  @Test
  void missingAnalysisSegmentsDoNotProduceFill() {
    assertFalse(WinrateGraph.shouldFillSegment(true, true));
    assertTrue(WinrateGraph.shouldFillSegment(true, false));
    assertFalse(WinrateGraph.shouldFillSegment(false, false));
  }

  @Test
  void fillOffAndDualMetricsDoNotFill() {
    assertFalse(WinrateGraph.resolveRenderableMetrics(true, false, true).areaFillEligible(false));
    assertFalse(WinrateGraph.resolveRenderableMetrics(true, true, true).areaFillEligible(true));
    assertFalse(WinrateGraph.shouldFillSegment(false, false));
  }

  @Test
  void fillGeometryLeavesEndpointCoordinatesUnchanged() {
    double x1 = 7;
    double y1 = 15;
    double x2 = 41;
    double y2 = 95;
    double baselineY = 40;

    WinrateGraph.baselineFillShapes(x1, y1, x2, y2, baselineY);

    assertEquals(7, x1, EPSILON);
    assertEquals(15, y1, EPSILON);
    assertEquals(41, x2, EPSILON);
    assertEquals(95, y2, EPSILON);
    assertEquals(40, baselineY, EPSILON);
  }

  @Test
  void fillAlphaStaysBelowOpaqueCurveColor() {
    Color curve = new Color(100, 180, 255);
    Color above = WinrateGraph.resolveAboveBaselineFillColor(curve);
    Color below = WinrateGraph.resolveBelowBaselineFillColor(new Color(38, 44, 52));

    assertEquals(100, above.getRed());
    assertEquals(180, above.getGreen());
    assertEquals(255, above.getBlue());
    assertEquals(52, above.getAlpha());
    assertEquals(42, below.getAlpha());
    assertTrue(above.getAlpha() < curve.getAlpha());
    assertTrue(below.getAlpha() < curve.getAlpha());
  }

  private static Point2D.Double point(double x, double y) {
    return new Point2D.Double(x, y);
  }

  private static void assertVertices(List<Point2D.Double> actual, Point2D.Double... expected) {
    assertEquals(expected.length, actual.size());
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i].x, actual.get(i).x, EPSILON, "x at " + i);
      assertEquals(expected[i].y, actual.get(i).y, EPSILON, "y at " + i);
    }
  }

  private static void assertVerticesStayOnOrAboveBaseline(
      WinrateGraph.BaselineFillShape shape, double baselineY) {
    for (Point2D.Double vertex : shape.vertices) {
      assertTrue(vertex.y <= baselineY + EPSILON, "vertex crossed below baseline: " + vertex);
    }
  }

  private static void assertVerticesStayOnOrBelowBaseline(
      WinrateGraph.BaselineFillShape shape, double baselineY) {
    for (Point2D.Double vertex : shape.vertices) {
      assertTrue(vertex.y >= baselineY - EPSILON, "vertex crossed above baseline: " + vertex);
    }
  }
}
