package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;

/** Converts KataGo ownership values into the counts shown by the position estimate dialog. */
public final class OwnershipEstimate {
  private OwnershipEstimate() {}

  public static Result calculate(
      int width,
      int height,
      Stone[] stones,
      int blackCaptures,
      int whiteCaptures,
      List<Double> blackOwnership,
      double threshold) {
    int pointCount = Math.multiplyExact(width, height);
    if (width <= 0
        || height <= 0
        || stones == null
        || stones.length != pointCount
        || blackOwnership == null
        || blackOwnership.size() < pointCount) {
      throw new IllegalArgumentException("Ownership data does not match the current board");
    }

    double effectiveThreshold = Double.isFinite(threshold) ? Math.max(0.0, threshold) : 0.0;
    ArrayList<Double> normalized = new ArrayList<>(pointCount);
    for (int i = 0; i < pointCount; i++) {
      Double value = blackOwnership.get(i);
      double ownership = value == null || !Double.isFinite(value) ? 0.0 : value;
      normalized.add(Math.abs(ownership) >= effectiveThreshold ? ownership : 0.0);
    }
    ArrayList<Double> rendered = new ArrayList<>(normalized);

    int blackPoints = 0;
    int whitePoints = 0;
    int blackAlive = 0;
    int whiteAlive = 0;
    int blackPrisoners = 0;
    int whitePrisoners = 0;
    for (int i = 0; i < pointCount; i++) {
      Stone stone = stones[i];
      double ownership = normalized.get(i);
      if (stone == Stone.BLACK) {
        if (ownership < 0) {
          blackPrisoners++;
          whitePoints++;
        } else {
          blackAlive++;
          if (ownership > 0) rendered.set(i, 0.0);
        }
      } else if (stone == Stone.WHITE) {
        if (ownership > 0) {
          whitePrisoners++;
          blackPoints++;
        } else {
          whiteAlive++;
          if (ownership < 0) rendered.set(i, 0.0);
        }
      } else if (ownership > 0) {
        double pointOwnership = surroundedBySign(normalized, width, height, i, true) ? ownership : 0.0;
        rendered.set(i, pointOwnership);
        if (pointOwnership > 0) blackPoints++;
      } else if (ownership < 0) {
        double pointOwnership = surroundedBySign(normalized, width, height, i, false) ? ownership : 0.0;
        rendered.set(i, pointOwnership);
        if (pointOwnership < 0) whitePoints++;
      }
    }

    return new Result(
        List.copyOf(rendered),
        blackCaptures,
        whiteCaptures,
        blackPrisoners,
        whitePrisoners,
        blackPoints,
        whitePoints,
        blackAlive,
        whiteAlive);
  }

  private static boolean surroundedBySign(
      List<Double> ownership, int width, int height, int index, boolean positive) {
    int x = index % width;
    int y = index / width;
    if (x > 0 && !matchesSign(ownership.get(index - 1), positive)) return false;
    if (x + 1 < width && !matchesSign(ownership.get(index + 1), positive)) return false;
    if (y > 0 && !matchesSign(ownership.get(index - width), positive)) return false;
    return y + 1 >= height || matchesSign(ownership.get(index + width), positive);
  }

  private static boolean matchesSign(double value, boolean positive) {
    return positive ? value > 0 : value < 0;
  }

  public record Result(
      List<Double> renderedOwnership,
      int blackCaptures,
      int whiteCaptures,
      int blackPrisoners,
      int whitePrisoners,
      int blackPoints,
      int whitePoints,
      int blackAlive,
      int whiteAlive) {}
}
