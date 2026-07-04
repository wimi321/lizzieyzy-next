package featurecat.lizzie.tools;

import java.util.ResourceBundle;

/** Lightweight class-loading warmup used only while building AppCDS archives. */
public final class CdsWarmup {
  private static final String[] REPRESENTATIVE_CLASSES = {
    "featurecat.lizzie.Lizzie",
    "featurecat.lizzie.Config",
    "featurecat.lizzie.rules.Board",
    "featurecat.lizzie.rules.BoardHistoryList",
    "featurecat.lizzie.rules.SGFParser",
    "featurecat.lizzie.analysis.Leelaz",
    "featurecat.lizzie.analysis.EngineManager",
    "featurecat.lizzie.analysis.AnalysisEngine",
    "featurecat.lizzie.analysis.PlayerStrengthEstimator",
    "featurecat.lizzie.gui.LizzieFrame",
    "featurecat.lizzie.gui.BoardRenderer",
    "featurecat.lizzie.gui.WinrateGraph",
    "featurecat.lizzie.gui.ConfigDialog2",
    "featurecat.lizzie.gui.KataGoAutoSetupDialog",
    "featurecat.lizzie.gui.BrowserFrame",
    "featurecat.lizzie.util.KataGoRuntimeHelper",
    "featurecat.lizzie.util.KataGoAutoSetupHelper",
    "featurecat.lizzie.util.Utils",
  };

  private CdsWarmup() {}

  public static void main(String[] args) throws Exception {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    ResourceBundle.getBundle("l10n.DisplayStrings");
    for (String className : REPRESENTATIVE_CLASSES) {
      try {
        Class.forName(className, false, loader);
      } catch (LinkageError | ClassNotFoundException e) {
        System.err.println("CDS warmup skipped class: " + className + " (" + e + ")");
      }
    }
    System.out.println("LizzieYzy Next CDS warmup completed.");
  }
}
