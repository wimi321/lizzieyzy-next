package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** EngineCommandSink 的生产实现，薄包装 Lizzie.leelaz 的 GTP 接口。 */
public final class LeelazEngineCommandSink implements EngineCommandSink {

  @Override
  public void playMove(Stone color, String coord) {
    Lizzie.leelaz.playMove(color, coord);
  }

  @Override
  public void undo() {
    Lizzie.leelaz.undo();
  }

  @Override
  public void clear() {
    Lizzie.leelaz.clear();
  }

  @Override
  public void clearBestMoves() {
    Lizzie.leelaz.clearBestMoves();
  }

  /**
   * 兜底重 sync：清空引擎并把 target 对应的盘面恢复给引擎。
   *
   * <p>序列里所有发命令前 stop ponder、用 NoPonder 版 play，避免每个 play 都触发 kata-analyze 重启，否则 KataGo
   * 会在中间盘面的多次重启过程中产生大量短暂的 info 行。序列发完才统一 ponder() 启动新分析。
   *
   * <p>exact snapshot restore module 负责最近 SNAPSHOT、loadsgf 与真实 tail；没有 SNAPSHOT
   * 时才保留原 root replay。dummy 与 SNAPSHOT 节点本身跳过。
   */
  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    Leelaz engine = Lizzie.leelaz;
    if (target == null) {
      engine.clear();
      return;
    }

    boolean resumePonder = engine.isPondering();
    Leelaz mirror = engine.resolveLoadSgfMirrorEngine();
    Leelaz.ExactSnapshotRestoreAdmission admission =
        engine.captureExactSnapshotRestoreAdmission(
            Leelaz.ExactSnapshotRestoreOwner.ORDINARY, null, mirror);
    java.util.Optional<ExactSnapshotEngineRestore.PreparedRestore> preparedRestore =
        ExactSnapshotEngineRestore.prepare(admission, target);
    // 先停 ponder，避免后续 sync 命令期间 KataGo 仍在跑旧 ponder 输出 info 行
    engine.notPondering();
    sendStopOrName(engine, admission);
    if (mirror != null) {
      sendStopOrName(mirror, admission);
    }

    if (preparedRestore.isPresent()) {
      preparedRestore.orElseThrow().execute();
      if (resumePonder) {
        engine.ponder();
      }
      if (TrialDiag.ENABLED) {
        System.out.printf(
            "[trial-resync] target moveNum=%d exact snapshot restore done wasPondering=%s%n",
            target.getData() == null ? -1 : target.getData().moveNumber, resumePonder);
      }
      return;
    }

    List<BoardHistoryNode> chain = buildRootReplayChain(target);
    // 用 clearWithoutPonder 清盘且不重启 ponder，避免 clear→ponder→play→ponder 链路
    engine.clearWithoutPonder();
    if (TrialDiag.ENABLED) {
      System.out.printf(
          "[trial-resync] target moveNum=%d snapshotAnchor=%s chainLen=%d%n",
          target.getData() == null ? -1 : target.getData().moveNumber,
          "(root)",
          chain.size());
    }

    int played = 0;
    for (BoardHistoryNode n : chain) {
      played += replayNodeNoPonder(engine, n) ? 1 : 0;
    }

    // 序列发完，再启动 ponder。这时 KataGo 已稳定到 target 局面，info 行才会针对正确盘面
    if (resumePonder) {
      engine.ponder();
    }

    if (TrialDiag.ENABLED) {
      System.out.printf(
          "[trial-resync] target moveNum=%d done, %d nodes replayed (skipped %d) wasPondering=%s%n",
          target.getData() == null ? -1 : target.getData().moveNumber,
          played,
          chain.size() - played,
          resumePonder);
    }
  }

  private static void sendStopOrName(
      Leelaz target, Leelaz.ExactSnapshotRestoreAdmission admission) {
    String command = target.isKatago ? "stop" : "name";
    if (!target.sendCommandToCapturedRestoreTarget(command, admission)) {
      throw new IllegalStateException("Exact snapshot restore precommand was rejected: " + command);
    }
  }

  /** 构造从 root 到 target 的真实回放序列；dummy 与 SNAPSHOT 节点都跳过。 */
  private static List<BoardHistoryNode> buildRootReplayChain(BoardHistoryNode target) {
    List<BoardHistoryNode> chain = new ArrayList<>();
    for (BoardHistoryNode n = target; n != null; n = n.previous().orElse(null)) {
      BoardData d = n.getData();
      if (d == null || d.dummy || d.isSnapshotNode()) continue;
      chain.add(n);
    }
    Collections.reverse(chain);
    return chain;
  }

  private boolean replayNodeNoPonder(Leelaz engine, BoardHistoryNode n) {
    BoardData d = n.getData();
    if (d == null || d.dummy) return false;
    if (d.isPassNode()) {
      if (TrialDiag.ENABLED) System.out.printf("[trial-replay] play %s pass%n", d.lastMoveColor);
      engine.playMoveNoPonder(d.lastMoveColor, "pass");
      return true;
    }
    if (!d.lastMove.isPresent()) return false;
    int[] xy = d.lastMove.get();
    String coord = Board.convertCoordinatesToName(xy[0], xy[1]);
    if (TrialDiag.ENABLED) {
      System.out.printf(
          "[trial-replay] play %s %s (moveNum=%d blackToPlayAfter=%s)%n",
          d.lastMoveColor, coord, d.moveNumber, d.blackToPlay);
    }
    engine.playMoveNoPonder(d.lastMoveColor, coord);
    return true;
  }
}
