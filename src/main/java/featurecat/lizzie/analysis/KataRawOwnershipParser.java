package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Incrementally extracts whiteOwnership from a kata-raw-nn response. */
final class KataRawOwnershipParser {
  private int width;
  private int height;
  private boolean collecting;
  private final ArrayList<Double> blackOwnership = new ArrayList<>();

  synchronized void begin(int width, int height) {
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    collecting = false;
    blackOwnership.clear();
  }

  synchronized Optional<List<Double>> accept(String line) {
    String normalized = line == null ? "" : line.trim();
    if (normalized.equals("whiteOwnership")) {
      collecting = true;
      blackOwnership.clear();
      return Optional.empty();
    }
    if (!collecting || normalized.isEmpty()) {
      return Optional.empty();
    }

    String[] values = normalized.split("\\s+");
    if (values.length != width) {
      reset();
      return Optional.empty();
    }
    for (String value : values) {
      try {
        blackOwnership.add(-Double.parseDouble(value));
      } catch (NumberFormatException e) {
        blackOwnership.add(0.0);
      }
    }
    int pointCount = width * height;
    if (blackOwnership.size() < pointCount) {
      return Optional.empty();
    }
    List<Double> result = List.copyOf(blackOwnership.subList(0, pointCount));
    reset();
    return Optional.of(result);
  }

  synchronized void reset() {
    collecting = false;
    blackOwnership.clear();
  }
}
