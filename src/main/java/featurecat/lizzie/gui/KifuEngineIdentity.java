package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.Leelaz;

/** Reader identity checks for import completion, including the cold-start offline placeholder. */
final class KifuEngineIdentity {
  private KifuEngineIdentity() {}

  static boolean remainsCurrent(
      Leelaz primary, Object primaryReader, Leelaz mirror, Object mirrorReader, boolean empty) {
    // The import owner separately checks the selected engine generation, game and rules.
    // A cold-start placeholder has never owned a reader; it is valid only while still offline.
    boolean primaryMatches =
        primary == null
            ? primaryReader == null
            : primaryReader == null
                ? empty
                    && mirror == null
                    && !primary.isStarted()
                    && primary.engineIncarnationToken() == null
                : primary.isCurrentEngineIncarnationToken(primaryReader);
    return primaryMatches
        && (mirror == null
            ? mirrorReader == null
            : mirror.isCurrentEngineIncarnationToken(mirrorReader));
  }
}
