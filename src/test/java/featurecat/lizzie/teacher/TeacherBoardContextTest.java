package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.*;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TeacherBoardContextTest {
  static BoardHistoryNode position() {
    BoardHistoryNode node = new BoardHistoryNode(BoardData.empty(19, 19));
    stone(node, 0, 0, Stone.WHITE);
    stone(node, 1, 0, Stone.BLACK);
    MoveData move = new MoveData();
    move.coordinate = "A18";
    move.variation = List.of("A18", "Q16", "D4");
    move.playouts = 400;
    move.winrate = 60;
    move.scoreMean = 3;
    node.getData().bestMoves = List.of(move);
    return node;
  }

  private static void stone(BoardHistoryNode node, int x, int y, Stone stone) {
    node.getData().stones[Board.getIndex(x, y)] = stone;
    node.getData().zobrist.toggleStone(x, y, stone);
  }

  @Test
  void freezesCorrectOrientationAndCountsCaptureWithoutMutatingSource() {
    BoardHistoryNode node = position();
    Stone[] original = node.getData().stones.clone();
    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    assertTrue(position.board.facts().contains("W stones=[A19] liberties=[A18]"));
    assertTrue(position.board.facts().contains("B stones=[B19]"));
    assertEquals(1, position.board.lines.get(0).frames().get(1).captures());
    assertEquals(Stone.EMPTY, position.board.lines.get(0).frames().get(1).stones().get(0));
    assertArrayEquals(original, node.getData().stones);
    assertTrue(node.variations.isEmpty());
    assertEquals(0, node.getData().blackCaptures);
    node.getData().stones[0] = Stone.EMPTY;
    assertEquals(Stone.WHITE, position.board.stones.get(0));
  }

  @Test
  void truncatesIllegalVariationAndPromptNeverIncludesItsSuffix() {
    BoardHistoryNode node = position();
    node.getData().bestMoves.get(0).variation = List.of("A18", "A18", "T1");
    TeacherEvidence.Position position = TeacherEvidence.current(node).orElseThrow();
    assertTrue(position.board.lines.get(0).truncated());
    assertEquals(List.of("A18"), position.board.lines.get(0).moves());
    String prompt = TeacherPromptBuilder.formatPosition(position, Locale.ENGLISH);
    assertFalse(prompt.contains("T1"));
    assertTrue(prompt.contains("captures=1"));
    assertFalse(TeacherVerifier.verify("Play T1", position).ok());
    assertTrue(
        TeacherVerifier.verify("The white stone at A19 has one liberty at A18.", position).ok());
  }

  @Test
  void passDoesNotInvokeForegroundHistoryHooks() {
    BoardHistoryNode node = position();
    node.getData().bestMoves.get(0).variation = List.of("A18", "pass", "D4");
    assertEquals(3, TeacherEvidence.current(node).orElseThrow().board.lines.get(0).moves().size());
    assertTrue(node.variations.isEmpty());
  }

  @Test
  void recordedPassKeepsColorOrderAndStopsAtSetupNodes() {
    BoardHistoryNode node = position();
    BoardData source = node.getData();
    BoardData pass =
        BoardData.pass(
            source.stones.clone(),
            Stone.BLACK,
            false,
            source.zobrist.clone(),
            1,
            new int[361],
            0,
            0,
            50,
            0);
    BoardHistoryNode child = new BoardHistoryNode(pass);
    node.variations.add(child);
    node.setPreviousForChild(child);
    BoardData after =
        BoardData.move(
            pass.stones.clone(),
            new int[] {15, 3},
            Stone.WHITE,
            true,
            pass.zobrist.clone(),
            2,
            new int[361],
            0,
            0,
            50,
            0);
    BoardHistoryNode reply = new BoardHistoryNode(after);
    child.variations.add(reply);
    child.setPreviousForChild(reply);
    var p = TeacherEvidence.current(node).orElseThrow();
    assertEquals(List.of("pass", "Q16"), p.playedContinuation);
    var line = p.board.lines.get(0);
    assertEquals("B", line.frames().get(1).color());
    assertEquals("W", line.frames().get(2).color());
    after.lastMoveColor = Stone.BLACK;
    assertEquals(List.of("pass"), TeacherEvidence.current(node).orElseThrow().playedContinuation);
  }

  @Test
  void usesPointLossRatherThanWinrateLossAndRetainsPlayedCandidateOutsideTopThree() {
    BoardHistoryNode node = position();
    MoveData best = node.getData().bestMoves.get(0);
    MoveData played = new MoveData();
    played.coordinate = "D4";
    played.winrate = 20;
    played.scoreMean = 2.5;
    played.playouts = 250;
    played.variation = List.of("D4");
    node.getData().bestMoves = List.of(best, best, best, played);
    BoardData childData =
        BoardData.move(
            node.getData().stones.clone(),
            new int[] {3, 15},
            Stone.BLACK,
            false,
            node.getData().zobrist.clone(),
            1,
            new int[361],
            0,
            0,
            50,
            0);
    BoardHistoryNode child = new BoardHistoryNode(childData);
    node.variations.add(child);
    node.setPreviousForChild(child);
    TeacherEvidence.Position p = TeacherEvidence.current(node).orElseThrow();
    assertEquals(4, p.candidates.size());
    assertEquals(40, p.actualWinrateLoss.orElseThrow());
    assertEquals(0.5, p.actualScoreLoss().orElseThrow());
  }

  @Test
  void snapshotMarkerIsNotTaughtAsARecordedMove() {
    var parent = position();
    var source = parent.getData();
    var snapshot =
        BoardData.snapshot(
            source.stones.clone(),
            java.util.Optional.of(new int[] {3, 15}),
            Stone.BLACK,
            false,
            source.zobrist.clone(),
            1,
            new int[361],
            0,
            0,
            50,
            0);
    var child = new BoardHistoryNode(snapshot);
    parent.variations.add(child);
    parent.setPreviousForChild(child);
    var evidence = TeacherEvidence.current(parent).orElseThrow();
    assertTrue(evidence.actualMove.isEmpty());
    assertTrue(evidence.playedContinuation.isEmpty());
  }

  @Test
  void wholeGameFreezesTheSelectedNodeNotAnotherNodeWithTheSameMoveNumber() {
    var root = new BoardHistoryNode(BoardData.empty(19, 19));
    var analyzed = position();
    root.variations.add(analyzed);
    root.setPreviousForChild(analyzed);
    var source = analyzed.getData();
    var next =
        new BoardHistoryNode(
            BoardData.move(
                source.stones.clone(),
                new int[] {0, 1},
                Stone.BLACK,
                false,
                source.zobrist.clone(),
                1,
                new int[361],
                0,
                0,
                50,
                0));
    analyzed.variations.add(next);
    analyzed.setPreviousForChild(next);
    var selected = TeacherEvidence.wholeGame(root).positions;
    assertEquals(1, selected.size());
    assertEquals(Stone.WHITE, selected.get(0).board.stones.get(0));
    assertEquals(Stone.EMPTY, root.getData().stones[0]);
  }
}
