package featurecat.lizzie.gui;

import featurecat.lizzie.theme.Theme;
import java.awt.Font;

/**
 * How the coordinates around the main board are drawn: bold or not, and how many times their usual
 * size. Bigger labels widen the board's margin, by 60% of their own growth, so that they keep clear
 * of the stones on the edge lines: at 200% a label is about 0.6 of a grid square tall and the
 * margin about 1.8 squares, as on a printed board.
 *
 * @param sizePercent the size in percent of the usual one; see {@link #SIZES}
 */
record CoordinateLabels(boolean bold, int sizePercent) {
  static final CoordinateLabels STANDARD = new CoordinateLabels(false, 100);

  /** The sizes the theme settings offer, in percent: standard, large and extra large. */
  static final int[] SIZES = {100, 140, 200};

  static CoordinateLabels of(Theme theme) {
    return theme == null
        ? STANDARD
        : new CoordinateLabels(theme.coordinateBold(), theme.coordinateSize());
  }

  /** The size as a factor, kept within the offered sizes. */
  double scale() {
    int bounded = Math.max(SIZES[0], Math.min(SIZES[SIZES.length - 1], sizePercent));
    return bounded / 100.0;
  }

  int fontStyle() {
    return bold ? Font.BOLD : Font.PLAIN;
  }

  /** The tallest a label may be next to stones of {@code stoneRadius}. */
  float maxHeight(int stoneRadius) {
    return (float) (stoneRadius * 4 / 5 * scale());
  }

  /** The widest a label may be next to stones of {@code stoneRadius}. */
  double maxWidth(int stoneRadius) {
    return stoneRadius * scale();
  }

  /** The board margin that leaves room for the labels, from the margin at the usual size. */
  double margin(double usualMargin) {
    return usualMargin * (1 + (scale() - 1) * MARGIN_GROWTH);
  }

  private static final double MARGIN_GROWTH = 0.6;

  /** The index in {@link #SIZES} nearest to {@code sizePercent}, for the settings' size list. */
  static int sizeIndex(int sizePercent) {
    int best = 0;
    for (int i = 1; i < SIZES.length; i++) {
      if (Math.abs(SIZES[i] - sizePercent) < Math.abs(SIZES[best] - sizePercent)) {
        best = i;
      }
    }
    return best;
  }
}
