package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import org.junit.jupiter.api.Test;

public class EngineFollowControllerTest {

  /** 构最简 BoardData：仅供 path 计算使用，不进入引擎。 */
  private BoardData mkData(int x, int y, Stone color) {
    return BoardData.move(
        new Stone[0], new int[] {x, y}, color, color != Stone.BLACK, null, 0, null, 0, 0, 0, 0);
  }

  @Test
  public void pathBetween_sameNode_emptyPath() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    EngineFollowController.Path p = EngineFollowController.pathBetween(root, root);
    assertEquals(0, p.undoCount);
    assertTrue(p.playSequence.isEmpty());
    assertFalse(p.needsResync);
  }

  @Test
  public void pathBetween_forwardOneStep() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    root.variations.add(child);
    root.setPreviousForChild(child);

    EngineFollowController.Path p = EngineFollowController.pathBetween(root, child);
    assertEquals(0, p.undoCount);
    assertEquals(1, p.playSequence.size());
    assertSame(child, p.playSequence.get(0));
  }

  @Test
  public void pathBetween_backwardTwoSteps() {
    BoardHistoryNode a = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode b = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    BoardHistoryNode c = new BoardHistoryNode(mkData(15, 15, Stone.WHITE));
    a.variations.add(b);
    a.setPreviousForChild(b);
    b.variations.add(c);
    b.setPreviousForChild(c);

    EngineFollowController.Path p = EngineFollowController.pathBetween(c, a);
    assertEquals(2, p.undoCount);
    assertTrue(p.playSequence.isEmpty());
  }

  @Test
  public void pathBetween_siblingJump() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode left = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    BoardHistoryNode right = new BoardHistoryNode(mkData(15, 15, Stone.BLACK));
    root.variations.add(left);
    root.setPreviousForChild(left);
    root.variations.add(right);
    root.setPreviousForChild(right);

    EngineFollowController.Path p = EngineFollowController.pathBetween(left, right);
    assertEquals(1, p.undoCount);
    assertEquals(1, p.playSequence.size());
    assertSame(right, p.playSequence.get(0));
  }
}
