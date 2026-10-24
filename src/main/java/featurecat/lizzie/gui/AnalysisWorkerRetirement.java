package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.AnalysisEngine;

/** The frame's event thread retires an idle worker before publishing its replacement. */
final class AnalysisWorkerRetirement {
  private AnalysisWorkerRetirement() {}

  static boolean releaseIdleIncompatible(LizzieFrame frame, boolean reusable) {
    AnalysisEngine previous = frame.analysisEngine;
    if (previous == null || reusable) return true;
    if (previous.hasRequestLifecycleInProgress()) return false;
    previous.clearRequestCallbacks();
    frame.analysisEngine = null;
    previous.normalQuit();
    return true;
  }
}
