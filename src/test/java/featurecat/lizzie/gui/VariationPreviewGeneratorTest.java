package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.Stone;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VariationPreviewGeneratorTest {
  @Test
  void capturedRenderingSurvivesLiveChangesAndDoesNotReusePublishedPixels() {
    Stone[] sourceStones = new Stone[6];
    Arrays.fill(sourceStones, Stone.EMPTY);
    sourceStones[0] = Stone.WHITE;
    BoardData source =
        BoardData.snapshot(
            sourceStones, Optional.empty(), Stone.EMPTY, true, null, 7, new int[6], 0, 0, 0, 0);
    List<String> variation = new ArrayList<>(List.of("B2", "C1"));
    List<String> visits = new ArrayList<>(List.of("1200", "2300"));
    Branch.Input input =
        new Branch.Input(new Branch.Position(3, 2, source, sourceStones, true), variation, visits, 2,
            false, true);
    VariationPreviewGenerator.Geometry geometry =
        new VariationPreviewGenerator.Geometry(3, 2, 120, 80, 20, 20, 40, 40, 12, 2, 1);
    BufferedImage shadow = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = shadow.createGraphics();
    try {
      g.setColor(new Color(0, 0, 0, 96));
      g.fillOval(0, 0, 25, 25);
    } finally {
      g.dispose();
    }
    VariationPreviewGenerator.Style style =
        new VariationPreviewGenerator.Style(
            false, false, false, false, true, false, false, false, false, true, true, 0,
            199, 199, -1, new Font("Dialog", Font.PLAIN, 12), null, null, shadow, 12,
            Color.ORANGE, Color.ORANGE,
            VariationPreviewGenerator.captureSourceStones(sourceStones, null),
            "pass");
    VariationPreviewGenerator.Result first =
        VariationPreviewGenerator.generate(input, geometry, style);
    assertEquals(Color.BLACK.getRGB(), first.stones().getRGB(60, 20));
    assertEquals(Color.WHITE.getRGB(), first.stones().getRGB(100, 60));
    assertEquals(new Color(0, 0, 0, 96).getRGB(), first.shadows().getRGB(60, 20));
    assertEquals(Color.ORANGE.getRGB(), first.annotations().getRGB(49, 1));
    int[] firstStones = pixels(first.stones());
    int[] firstShadows = pixels(first.shadows());
    int[] firstAnnotations = pixels(first.annotations());

    int originalWidth = Board.boardWidth;
    int originalHeight = Board.boardHeight;
    Config originalConfig = Lizzie.config;
    Font originalFont = LizzieFrame.uiFont;
    try {
      Board.boardWidth = 9;
      Board.boardHeight = 13;
      Lizzie.config = null;
      LizzieFrame.uiFont = new Font("Monospaced", Font.BOLD, 42);
      VariationPreviewGenerator.captureSourceStones(sourceStones, style);
      Arrays.fill(sourceStones, Stone.BLACK);
      variation.clear();
      visits.clear();
      VariationPreviewGenerator.captureSourceStones(sourceStones, style);

      VariationPreviewGenerator.Result repeated =
          VariationPreviewGenerator.generate(input, geometry, style);
      assertArrayEquals(firstStones, pixels(repeated.stones()));
      assertArrayEquals(firstShadows, pixels(repeated.shadows()));
      assertArrayEquals(firstAnnotations, pixels(repeated.annotations()));

      Branch.Input different =
          new Branch.Input(input.position, List.of("C2", "B1"), List.of("10", "20"), 2,
              false, true);
      VariationPreviewGenerator.Result next =
          VariationPreviewGenerator.generate(different, geometry, style);
      assertEquals(Color.BLACK.getRGB(), next.stones().getRGB(100, 20));
      assertEquals(0, next.stones().getRGB(60, 20));
      assertArrayEquals(firstStones, pixels(first.stones()));
      assertArrayEquals(firstShadows, pixels(first.shadows()));
      assertArrayEquals(firstAnnotations, pixels(first.annotations()));
    } finally {
      Board.boardWidth = originalWidth;
      Board.boardHeight = originalHeight;
      Lizzie.config = originalConfig;
      LizzieFrame.uiFont = originalFont;
    }
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }
}
