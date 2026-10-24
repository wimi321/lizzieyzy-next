package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.ForegroundRestoreResult;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.function.Consumer;

/** A flash request retained by the frame's existing manual-analysis start owner during import. */
final class FlashAnalysisAfterSync
    implements Runnable, Consumer<LizzieFrame.ManualAutoAnalysisStartFailure> {
  private final LizzieFrame frame;
  private final Leelaz primary;
  private final long primaryGeneration;
  private final boolean allGame;
  private final boolean allBranches;
  private final boolean batch;
  private final BoardHistoryNode root;
  private boolean resumeRequested;
  private boolean finished;
  private AnalysisEngine worker;

  FlashAnalysisAfterSync(LizzieFrame frame, Leelaz primary, boolean allGame, boolean allBranches) {
    this.frame = frame;
    this.primary = primary;
    this.primaryGeneration = Lizzie.capturePrimaryEngineGeneration(primary);
    this.allGame = allGame;
    this.allBranches = allBranches;
    this.batch = frame.isBatchAnalysisMode;
    this.root = frame.currentHistoryRoot();
  }

  boolean ownsBatch() {
    return batch;
  }

  boolean isCurrent() {
    return primary != null
        && AnalysisEngine.reusesPrimaryForManualAnalysis()
        && frame.currentHistoryRoot() == root
        && primaryGeneration >= 0L
        && Lizzie.capturePrimaryEngineGeneration(primary) == primaryGeneration;
  }

  boolean canUse(AnalysisEngine worker) {
    return isCurrent() && worker.usesSharedForegroundEngine();
  }

  @Override
  public void run() {
    if (isCurrent()) frame.flashAnalyzeGame(allGame, allBranches, this);
    else accept(LizzieFrame.ManualAutoAnalysisStartFailure.ENGINE_UNAVAILABLE);
  }

  @Override
  public void accept(LizzieFrame.ManualAutoAnalysisStartFailure failure) {
    finished = true;
    if (LizzieFrame.toolbar != null) LizzieFrame.toolbar.showAutoAnalysisStartFailure(failure);
  }

  void requestForegroundResume() {
    if (!finished && isCurrent()) resumeRequested = true;
  }

  void bind(AnalysisEngine worker) {
    this.worker = worker;
    worker.setCompletionCallback(this::completed);
    worker.setFailureCallback(result -> finished = true);
  }

  private void completed(ForegroundRestoreResult result) {
    if (finished) return;
    finished = true;
    if (resumeRequested
        && result.permitsForegroundAnalysis()
        && isCurrent()
        && frame.analysisEngine == worker
        && worker.isRunning()
        && !worker.hasRequestLifecycleInProgress()) {
      primary.resumePonderAfterForegroundLeaseIfAllowed();
    }
  }
}
