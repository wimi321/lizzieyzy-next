package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SnapshotEngineRestore;
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
   * <p>沿祖先链找到最近的 SNAPSHOT 节点：找到则用 loadsgf 恢复 stones，再回放此后 MOVE；找不到则从根 replay 到 target。dummy 与
   * SNAPSHOT 节点本身跳过。
   */
  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    if (target == null) {
      Lizzie.leelaz.clear();
      return;
    }

    boolean wasPondering = Lizzie.leelaz.isPondering();
    // 先停 ponder，避免后续 sync 命令期间 KataGo 仍在跑旧 ponder 输出 info 行
    Lizzie.leelaz.notPondering();
    Lizzie.leelaz.nameCmdfornoponder();

    BoardHistoryNode snapshotAnchor = findSnapshotAnchor(target);
    boolean usedSnapshot = false;
    if (snapshotAnchor != null) {
      BoardData snapshotData = snapshotAnchor.getData();
      if (SnapshotEngineRestore.restoreExactSnapshotIfNeeded(Lizzie.leelaz, snapshotData)) {
        usedSnapshot = true;
      } else if (TrialDiag.ENABLED) {
        System.out.printf(
            "[trial-resync] target moveNum=%d snapshot restore failed, fallback to root replay%n",
            target.getData() == null ? -1 : target.getData().moveNumber);
      }
    }
    List<BoardHistoryNode> chain =
        usedSnapshot ? buildChain(target, snapshotAnchor) : buildChain(target, null);
    if (!usedSnapshot) {
      // 用 clearWithoutPonder 清盘且不重启 ponder，避免 clear→ponder→play→ponder 链路
      Lizzie.leelaz.clearWithoutPonder();
    }
    if (TrialDiag.ENABLED) {
      System.out.printf(
          "[trial-resync] target moveNum=%d snapshotAnchor=%s chainLen=%d%n",
          target.getData() == null ? -1 : target.getData().moveNumber,
          usedSnapshot
              ? String.valueOf(
                  snapshotAnchor.getData() == null ? -1 : snapshotAnchor.getData().moveNumber)
              : "(root)",
          chain.size());
    }

    int played = 0;
    for (BoardHistoryNode n : chain) {
      played += replayNodeNoPonder(n) ? 1 : 0;
    }

    // 序列发完，再启动 ponder。这时 KataGo 已稳定到 target 局面，info 行才会针对正确盘面
    if (wasPondering) {
      Lizzie.leelaz.ponder();
    }

    if (TrialDiag.ENABLED) {
      System.out.printf(
          "[trial-resync] target moveNum=%d done, %d nodes replayed (skipped %d) wasPondering=%s%n",
          target.getData() == null ? -1 : target.getData().moveNumber,
          played,
          chain.size() - played,
          wasPondering);
    }
  }

  /** 沿祖先链向上找最近的 SNAPSHOT 节点；找不到返回 null。 */
  private static BoardHistoryNode findSnapshotAnchor(BoardHistoryNode target) {
    for (BoardHistoryNode n = target; n != null; n = n.previous().orElse(null)) {
      BoardData d = n.getData();
      if (d != null && d.isSnapshotNode()) return n;
    }
    return null;
  }

  /**
   * 构造 target 之下的回放序列。stopAt 非 null 时遇到 stopAt 即止（不含 stopAt 本身），用于 snapshot 路径； stopAt 为 null
   * 时一直走到根，用于 root replay。dummy 与 SNAPSHOT 节点都跳过。
   */
  private static List<BoardHistoryNode> buildChain(
      BoardHistoryNode target, BoardHistoryNode stopAt) {
    List<BoardHistoryNode> chain = new ArrayList<>();
    for (BoardHistoryNode n = target; n != null && n != stopAt; n = n.previous().orElse(null)) {
      BoardData d = n.getData();
      if (d == null || d.dummy || d.isSnapshotNode()) continue;
      chain.add(n);
    }
    Collections.reverse(chain);
    return chain;
  }

  private boolean replayNodeNoPonder(BoardHistoryNode n) {
    BoardData d = n.getData();
    if (d == null || d.dummy) return false;
    if (d.isPassNode()) {
      if (TrialDiag.ENABLED) System.out.printf("[trial-replay] play %s pass%n", d.lastMoveColor);
      Lizzie.leelaz.playMoveNoPonder(d.lastMoveColor, "pass");
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
    Lizzie.leelaz.playMoveNoPonder(d.lastMoveColor, coord);
    return true;
  }
}
