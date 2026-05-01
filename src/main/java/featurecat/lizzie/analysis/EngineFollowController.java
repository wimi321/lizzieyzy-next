package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineFollowController {

  private static final Logger LOGGER = LoggerFactory.getLogger(EngineFollowController.class);

  private final EngineCommandSink sink;
  private final ExecutorService executor;
  private volatile BoardHistoryNode currentEngineNode;
  private volatile boolean trialActive;

  public EngineFollowController(EngineCommandSink sink) {
    this.sink = sink;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "engine-follow-controller");
              t.setDaemon(true);
              return t;
            });
  }

  public boolean isTrialActive() {
    return trialActive;
  }

  /** 仅启动期使用：登记 controller 当前认为引擎所在节点。trial 期间禁止改写。 */
  public void setCurrentEngineNode(BoardHistoryNode n) {
    if (trialActive) {
      throw new IllegalStateException("setCurrentEngineNode forbidden while trial active");
    }
    this.currentEngineNode = n;
  }

  // 试下期间 sync 旁路（rebuildFromSnapshot / Board.place / tryApplySingleMoveRecovery）
  // 会绕过 controller 直接动引擎，currentEngineNode 跟踪不可靠；trial 三个入口都强制
  // 全量重建，不再用 currentEngineNode 算增量。
  public void onTrialEnter(BoardHistoryNode anchor) {
    trialActive = true;
    executor.submit(
        () -> {
          if (currentEngineNode != anchor) {
            forceResync(anchor);
          }
        });
  }

  public void onTrialDisplayNodeChanged(BoardHistoryNode node) {
    executor.submit(() -> forceResync(node));
  }

  public void onTrialExit(BoardHistoryNode mainlineTail) {
    executor.submit(
        () -> {
          forceResync(mainlineTail);
          trialActive = false;
        });
  }

  public void onMainlineAdvance(BoardHistoryNode newTail) {
    if (trialActive) return;
    // sync 路径上 Board.place 已发过 play，这里只对账游标避免双发。
    executor.submit(() -> currentEngineNode = newTail);
  }

  /** 测试钩子：等所有已派发任务跑完。不 shutdown executor。 */
  public void awaitIdle() throws Exception {
    executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
  }

  /** 兜底：让引擎按当前历史重新对齐。内部异常仅记日志，不重抛。 */
  public void forceResync(BoardHistoryNode target) {
    try {
      sink.resyncFromCurrentHistory(target);
      sink.clearBestMoves();
      currentEngineNode = target;
    } catch (RuntimeException ex) {
      LOGGER.error("forceResync failed", ex);
    }
  }
}
