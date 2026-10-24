package featurecat.lizzie.gui;

/** The existing single pending kifu-open continuation and its superseded notification. */
record DeferredKifuOpen(Runnable continuation, Runnable superseded) {
  void run() {
    if (continuation != null) continuation.run();
  }

  void notifySuperseded() {
    if (superseded != null) superseded.run();
  }
}
