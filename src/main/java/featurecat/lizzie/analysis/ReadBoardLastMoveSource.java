package featurecat.lizzie.analysis;

enum ReadBoardLastMoveSource {
  LEGACY_UNKNOWN,
  UNKNOWN,
  NONE,
  RED_BLUE_MARKER,
  FOX_CORNER_FLIP,
  DEVIATION,
  STONE_COUNT;

  static ReadBoardLastMoveSource parse(String token) {
    if ("none".equals(token)) {
      return NONE;
    }
    if ("redBlueMarker".equals(token)) {
      return RED_BLUE_MARKER;
    }
    if ("foxCornerFlip".equals(token)) {
      return FOX_CORNER_FLIP;
    }
    if ("deviation".equals(token)) {
      return DEVIATION;
    }
    if ("stoneCount".equals(token)) {
      return STONE_COUNT;
    }
    return UNKNOWN;
  }

  boolean isTrustedVisualMarker() {
    return this == RED_BLUE_MARKER || this == FOX_CORNER_FLIP;
  }
}
