package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    // 试下期间 sync 旁路（rebuildFromSnapshot / Board.place / tryApplySingleMoveRecovery）
    // 会反复改写 board 历史与引擎位置，currentEngineNode 跟踪不可靠。
    // 每次切换都强制按当前历史重建，避免 applySwitch 用错位游标发出错误 play 序列导致吞子。
    executor.submit(() -> forceResync(node));
  }

  public void onTrialExit(BoardHistoryNode mainlineTail) {
    executor.submit(
        () -> {
          // 试下期间 sync 路径（Board.place / nextMove / nextVariation / tryApplySingleMoveRecovery）
          // 会绕过 controller 直接动引擎，currentEngineNode 与实际引擎位置可能错位。
          // 退出时一律强制重建（clear_board + loadsgf 当前历史），避免 applySwitch 用错位的 path
          // 发出错误的 undo/play 序列导致引擎位置错乱。
          forceResync(mainlineTail);
          trialActive = false;
        });
  }

  public void onMainlineAdvance(BoardHistoryNode newTail) {
    if (trialActive) return;
    // 同步路径上 Board.place 已经把 play 发给引擎，这里只对账游标，避免重复 play 导致 illegal move。
    executor.submit(() -> currentEngineNode = newTail);
  }

  /** 测试钩子：等所有已派发任务跑完。不 shutdown executor。 */
  public void awaitIdle() throws Exception {
    executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
  }

  private void applySwitch(BoardHistoryNode target) {
    try {
      Path p = pathBetween(currentEngineNode, target);
      if (p.needsResync) {
        forceResync(target);
        return;
      }
      for (int i = 0; i < p.undoCount; i++) sink.undo();
      for (BoardHistoryNode n : p.playSequence) {
        BoardData d = n.getData();
        if (d == null) continue;
        Stone color = d.lastMoveColor;
        if (d.isPassNode()) {
          sink.playMove(color, "pass");
          continue;
        }
        if (d.lastMove == null || !d.lastMove.isPresent()) continue;
        int[] xy = d.lastMove.get();
        sink.playMove(color, Board.convertCoordinatesToName(xy[0], xy[1]));
      }
      sink.clearBestMoves();
      currentEngineNode = target;
    } catch (RuntimeException ex) {
      LOGGER.error("engine follow switch failed, force resync", ex);
      forceResync(target);
    }
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

  public static final class Path {
    public final int undoCount;
    public final List<BoardHistoryNode> playSequence;
    public final boolean needsResync;

    private Path(int undoCount, List<BoardHistoryNode> playSequence, boolean needsResync) {
      this.undoCount = undoCount;
      this.playSequence = playSequence;
      this.needsResync = needsResync;
    }

    static Path of(int undo, List<BoardHistoryNode> play) {
      return new Path(undo, play, false);
    }

    static Path resync() {
      return new Path(0, Collections.emptyList(), true);
    }
  }

  /** 算 from -> to 的增量切换路径。任一节点是 SNAPSHOT 或祖先无交集时返回 needsResync。dummy 占位节点不发 GTP。 */
  public static Path pathBetween(BoardHistoryNode from, BoardHistoryNode to) {
    if (from == to) return Path.of(0, Collections.emptyList());
    if (from == null || to == null) return Path.resync();

    Set<BoardHistoryNode> fromAncestors = new HashSet<>();
    for (BoardHistoryNode n = from; n != null; n = n.previous().orElse(null)) {
      fromAncestors.add(n);
    }

    List<BoardHistoryNode> toUpward = new ArrayList<>();
    BoardHistoryNode lca = null;
    for (BoardHistoryNode n = to; n != null; n = n.previous().orElse(null)) {
      if (fromAncestors.contains(n)) {
        lca = n;
        break;
      }
      toUpward.add(n);
    }
    if (lca == null) return Path.resync();

    int undoCount = 0;
    for (BoardHistoryNode n = from; n != lca; n = n.previous().orElse(null)) {
      if (isSnapshot(n)) return Path.resync();
      undoCount++;
    }

    Collections.reverse(toUpward);
    List<BoardHistoryNode> filtered = new ArrayList<>(toUpward.size());
    for (BoardHistoryNode n : toUpward) {
      if (isSnapshot(n)) return Path.resync();
      // dummy 占位节点不发 GTP（占位用，不是真正的一手）
      BoardData d = n.getData();
      if (d != null && d.dummy) continue;
      filtered.add(n);
    }
    return Path.of(undoCount, filtered);
  }

  private static boolean isSnapshot(BoardHistoryNode n) {
    BoardData d = n.getData();
    return d != null && d.isSnapshotNode();
  }
}
