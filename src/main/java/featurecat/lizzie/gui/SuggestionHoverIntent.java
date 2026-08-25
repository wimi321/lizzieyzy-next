package featurecat.lizzie.gui;

import java.util.Objects;
import javax.swing.Timer;

/** Delays expensive variation rendering until the pointer has settled on a candidate. */
final class SuggestionHoverIntent {
  static final int DEFAULT_DELAY_MS = 200;

  private final Runnable onReady;
  private final Timer timer;
  private int targetX = -1;
  private int targetY = -1;
  private boolean tracking;
  private boolean ready;

  SuggestionHoverIntent(Runnable onReady) {
    this(DEFAULT_DELAY_MS, onReady);
  }

  SuggestionHoverIntent(int delayMs, Runnable onReady) {
    if (delayMs < 0) {
      throw new IllegalArgumentException("delayMs must not be negative");
    }
    this.onReady = Objects.requireNonNull(onReady, "onReady");
    timer = new Timer(delayMs, event -> reveal());
    timer.setRepeats(false);
  }

  void arm(int x, int y) {
    if (tracking && targetX == x && targetY == y) {
      return;
    }
    targetX = x;
    targetY = y;
    tracking = true;
    ready = false;
    timer.restart();
  }

  void cancel() {
    timer.stop();
    tracking = false;
    ready = false;
    targetX = -1;
    targetY = -1;
  }

  boolean permits(int x, int y) {
    return !tracking || targetX != x || targetY != y || ready;
  }

  boolean isPending() {
    return tracking && !ready;
  }

  void reveal() {
    if (!tracking || ready) {
      return;
    }
    timer.stop();
    ready = true;
    onReady.run();
  }
}
