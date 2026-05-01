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
   * 兜底重 sync：清空引擎，沿 target 祖先链从根回放到 target。 跳过 dummy / SNAPSHOT 节点（SNAPSHOT 在本路径下尽力跳过；含 SNAPSHOT
   * 的链由 controller 上游决定是否走此 sink 兜底）。
   */
  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    Lizzie.leelaz.clear();
    if (target == null) return;

    List<BoardHistoryNode> chain = new ArrayList<>();
    for (BoardHistoryNode n = target; n != null; n = n.previous().orElse(null)) {
      chain.add(n);
    }
    Collections.reverse(chain);

    for (BoardHistoryNode n : chain) {
      BoardData d = n.getData();
      if (d == null) continue;
      if (d.dummy) continue;
      if (d.isSnapshotNode()) continue;
      if (d.isPassNode()) {
        Lizzie.leelaz.playMove(d.lastMoveColor, "pass");
        continue;
      }
      if (!d.lastMove.isPresent()) continue;
      int[] xy = d.lastMove.get();
      Lizzie.leelaz.playMove(d.lastMoveColor, Board.convertCoordinatesToName(xy[0], xy[1]));
    }
  }
}
