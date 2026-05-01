package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EngineFollowController {

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
