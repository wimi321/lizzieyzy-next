package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

public class EngineFollowControllerTest {

  /** 构 moveNumber=1 的 move 节点。 */
  private BoardData mkMove(int x, int y, Stone color) {
    return BoardData.move(
        new Stone[0], new int[] {x, y}, color, color != Stone.BLACK, null, 1, null, 0, 0, 0, 0);
  }

  /** 测试用 sink：把所有调用记成字符串列表；可注入一次性异常。 */
  private static final class RecordingEngineCommandSink implements EngineCommandSink {
    final List<String> calls = new ArrayList<>();
    RuntimeException nextThrow;

    private void maybeThrow() {
      if (nextThrow != null) {
        RuntimeException ex = nextThrow;
        nextThrow = null;
        throw ex;
      }
    }

    @Override
    public void playMove(Stone color, String coord) {
      calls.add("play " + (color == Stone.BLACK ? "B" : "W") + " " + coord);
      maybeThrow();
    }

    @Override
    public void undo() {
      calls.add("undo");
      maybeThrow();
    }

    @Override
    public void clear() {
      calls.add("clear");
      maybeThrow();
    }

    @Override
    public void clearBestMoves() {
      calls.add("clearBestMoves");
      // clearBestMoves 不抛，避免 forceResync 兜底 path 也被打断
    }

    @Override
    public void resyncFromCurrentHistory(BoardHistoryNode target) {
      calls.add("resync");
    }
  }

  @Test
  public void displayNodeChanged_oneForward_forceResyncs() throws Exception {
    BoardHistoryNode anchor = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    anchor.variations.add(child);
    anchor.setPreviousForChild(child);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(anchor);
    c.onTrialEnter(anchor);
    c.onTrialDisplayNodeChanged(child);
    c.awaitIdle();

    // 试下切 displayNode 一律 forceResync，避免 sync 旁路改写引擎位置后 applySwitch 用错位游标
    // 算出错误 path（重复 play / 漏 undo）导致吞子。
    assertEquals(Arrays.asList("resync", "clearBestMoves"), sink.calls);
  }

  @Test
  public void displayNodeChanged_oneBack_forceResyncs() throws Exception {
    BoardHistoryNode anchor = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    anchor.variations.add(child);
    anchor.setPreviousForChild(child);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(child);
    c.onTrialEnter(child);
    c.onTrialDisplayNodeChanged(anchor);
    c.awaitIdle();

    assertEquals(Arrays.asList("resync", "clearBestMoves"), sink.calls);
  }

  @Test
  public void displayNodeChanged_siblingJump_forceResyncs() throws Exception {
    BoardHistoryNode root = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode left = new BoardHistoryNode(mkMove(3, 3, Stone.BLACK));
    BoardHistoryNode right = new BoardHistoryNode(mkMove(15, 15, Stone.BLACK));
    root.variations.add(left);
    root.setPreviousForChild(left);
    root.variations.add(right);
    root.setPreviousForChild(right);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(left);
    c.onTrialEnter(left);
    c.onTrialDisplayNodeChanged(right);
    c.awaitIdle();

    assertEquals(Arrays.asList("resync", "clearBestMoves"), sink.calls);
  }

  @Test
  public void mainlineAdvance_duringTrial_doesNotEmitGtp() throws Exception {
    BoardHistoryNode anchor = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode newTail = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    anchor.variations.add(newTail);
    anchor.setPreviousForChild(newTail);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(anchor);
    c.onTrialEnter(anchor);
    c.onMainlineAdvance(newTail);
    c.awaitIdle();

    // trial 激活时 mainline 推进忽略，且 onTrialEnter(anchor==current) 也不发命令
    assertTrue(sink.calls.isEmpty(), "expected no calls but got " + sink.calls);
  }

  @Test
  public void mainlineAdvance_outsideTrial_updatesCursorWithoutGtp() throws Exception {
    BoardHistoryNode root = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    root.variations.add(child);
    root.setPreviousForChild(child);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(root);
    c.onMainlineAdvance(child);
    c.awaitIdle();

    // Board.place 已在 sync 路径上 play 过；controller 仅对账游标，不再发命令避免双发。
    assertTrue(sink.calls.isEmpty(), "expected no calls but got " + sink.calls);

    // 游标已对齐到 child：进入 trial 时不应触发 forceResync。
    c.onTrialEnter(child);
    c.awaitIdle();
    assertTrue(sink.calls.isEmpty(), "expected no calls but got " + sink.calls);
  }

  @Test
  public void sinkException_triggersForceResync() throws Exception {
    BoardHistoryNode anchor = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    anchor.variations.add(child);
    anchor.setPreviousForChild(child);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    sink.nextThrow = new RuntimeException("boom");
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(anchor);
    c.onTrialEnter(anchor);
    c.onTrialDisplayNodeChanged(child);
    c.awaitIdle();

    assertTrue(sink.calls.contains("resync"), "expected resync in " + sink.calls);
  }

  @Test
  public void enter_whenEngineNodeMismatch_forceResyncs() throws Exception {
    BoardHistoryNode root = new BoardHistoryNode(mkMove(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.WHITE));
    root.variations.add(child);
    root.setPreviousForChild(child);

    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(root);
    c.onTrialEnter(child);
    c.awaitIdle();

    assertTrue(sink.calls.contains("resync"), "expected resync in " + sink.calls);
  }
}
