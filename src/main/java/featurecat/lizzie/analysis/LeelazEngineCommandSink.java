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
   * <p>沿祖先链找到最近的 SNAPSHOT 节点：找到则用 loadsgf 恢复 stones，再回放此后 MOVE；找不到则从根 replay 到 target。dummy 跳过。
   */
  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    Lizzie.leelaz.clear();
    if (target == null) return;

    List<BoardHistoryNode> chain = new ArrayList<>();
    BoardHistoryNode snapshotAnchor = null;
    for (BoardHistoryNode n = target; n != null; n = n.previous().orElse(null)) {
      BoardData d = n.getData();
      if (d != null && d.isSnapshotNode()) {
        snapshotAnchor = n;
        break;
      }
      chain.add(n);
    }
    Collections.reverse(chain);

    if (snapshotAnchor != null) {
      BoardData snapshotData = snapshotAnchor.getData();
      if (!SnapshotEngineRestore.restoreExactSnapshotIfNeeded(Lizzie.leelaz, snapshotData)) {
        // snapshot 数据无法 loadsgf，退化为从根 replay
        replayFromRoot(target);
        return;
      }
    }

    for (BoardHistoryNode n : chain) {
      replayNode(n);
    }
  }

  private void replayFromRoot(BoardHistoryNode target) {
    List<BoardHistoryNode> chain = new ArrayList<>();
    for (BoardHistoryNode n = target; n != null; n = n.previous().orElse(null)) {
      chain.add(n);
    }
    Collections.reverse(chain);
    for (BoardHistoryNode n : chain) {
      BoardData d = n.getData();
      if (d == null || d.dummy || d.isSnapshotNode()) continue;
      replayNode(n);
    }
  }

  private void replayNode(BoardHistoryNode n) {
    BoardData d = n.getData();
    if (d == null || d.dummy) return;
    if (d.isPassNode()) {
      Lizzie.leelaz.playMove(d.lastMoveColor, "pass");
      return;
    }
    if (!d.lastMove.isPresent()) return;
    int[] xy = d.lastMove.get();
    Lizzie.leelaz.playMove(d.lastMoveColor, Board.convertCoordinatesToName(xy[0], xy[1]));
  }
}
