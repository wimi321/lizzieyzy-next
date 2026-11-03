package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.Utils;
import java.text.MessageFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
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

  /** Queues a final root-position resync when starting-position setup mode exits. */
  public void onSetupModeExit(BoardHistoryNode rootSetup) {
    executor.submit(() -> forceResync(rootSetup));
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
      presentSnapshotPreparationFailure(ex);
    }
  }

  public static boolean presentSnapshotPreparationFailure(Throwable failure) {
    if (!(failure instanceof ExactSnapshotEngineRestore.Failure exactFailure)
        || exactFailure.category()
            != ExactSnapshotEngineRestore.FailureCategory.SNAPSHOT_PREPARATION) {
      return false;
    }
    return presentSnapshotPreparationFailureDetail(failure.getMessage());
  }

  static boolean presentSnapshotPreparationFailureDetail(String detail) {
    return show(snapshotPreparationFailureMessage(detail));
  }

  /**
   * Explains a restore failure that only another engine or setup can fix: the snapshot cannot be
   * prepared, or the engine cannot represent the position.
   */
  public static boolean presentPermanentRestoreFailure(Throwable failure) {
    String message = unsupportedPositionMessage(failure);
    return message == null ? presentSnapshotPreparationFailure(failure) : show(message);
  }

  static String unsupportedPositionMessage(Throwable failure) {
    if (!(failure instanceof ExactSnapshotEngineRestore.Failure exactFailure)
        || exactFailure.category()
            != ExactSnapshotEngineRestore.FailureCategory.UNSUPPORTED_REMOTE_POSITION
        || Lizzie.resourceBundle == null) {
      return null;
    }
    String detail = failure.getMessage();
    if (ExactSnapshotEngineRestore.WHITE_TO_PLAY_WITHOUT_TAIL_DETAIL.equals(detail)) {
      detail = Lizzie.resourceBundle.getString("EngineFollow.unsupportedPosition.whiteToPlay");
    } else if (ExactSnapshotEngineRestore.NO_HANDICAP_RESTORE_DETAIL.equals(detail)) {
      detail = Lizzie.resourceBundle.getString("EngineFollow.unsupportedPosition.handicap");
    }
    return MessageFormat.format(
        Lizzie.resourceBundle.getString("EngineFollow.unsupportedPosition"), detail);
  }

  private static boolean show(String message) {
    if (message == null) {
      return false;
    }
    if (SwingUtilities.isEventDispatchThread()) {
      Utils.showMsg(message);
    } else {
      SwingUtilities.invokeLater(() -> Utils.showMsg(message));
    }
    return true;
  }

  static String snapshotPreparationFailureMessage(Throwable failure) {
    if (!(failure instanceof ExactSnapshotEngineRestore.Failure exactFailure)
        || exactFailure.category()
            != ExactSnapshotEngineRestore.FailureCategory.SNAPSHOT_PREPARATION) {
      return null;
    }
    return snapshotPreparationFailureMessage(failure.getMessage());
  }

  private static String snapshotPreparationFailureMessage(String detail) {
    if (Lizzie.resourceBundle == null) {
      return null;
    }
    return MessageFormat.format(
        Lizzie.resourceBundle.getString("EngineFollow.snapshotPreparationFailed"),
        localizedSnapshotPreparationDetail(detail));
  }

  private static String localizedSnapshotPreparationDetail(String detail) {
    if (ExactSnapshotEngineRestore.NO_WRITABLE_SNAPSHOT_LOCATION_DETAIL.equals(detail)) {
      return Lizzie.resourceBundle.getString("EngineFollow.snapshotPreparation.noWritableLocation");
    }
    if (ExactSnapshotEngineRestore.UNSUPPORTED_SNAPSHOT_TRANSPORT_DETAIL.equals(detail)) {
      return Lizzie.resourceBundle.getString(
          "EngineFollow.snapshotPreparation.unsupportedTransport");
    }
    return detail;
  }
}
