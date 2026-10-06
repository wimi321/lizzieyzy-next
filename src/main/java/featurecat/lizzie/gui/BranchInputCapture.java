package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.List;
import java.util.function.Supplier;

/** Short, separate position/payload captures. No simulation or engine calls under either lock. */
final class BranchInputCapture {
  private final Board board;
  private final BoardHistoryList history;
  private final BoardHistoryNode current;
  private final Supplier<BoardHistoryNode> sourceSelector;
  private final Supplier<BoardHistoryNode> analysisSelector;
  private final BoardHistoryNode source;
  private final BoardHistoryNode analysis;
  final BoardData sourceData;
  final BoardData analysisData;
  private final long revision;
  private final int width;
  private final int height;
  private final boolean currentTurn;
  private final boolean sourceTurn;
  private final boolean analysisTurn;

  static BranchInputCapture begin(
      Board board,
      Supplier<BoardHistoryNode> sourceSelector,
      Supplier<BoardHistoryNode> analysisSelector) {
    synchronized (board) {
      BoardHistoryNode source = sourceSelector.get();
      BoardHistoryNode analysis = analysisSelector.get();
      if (source == null || analysis == null) return null;
      return new BranchInputCapture(board, sourceSelector, analysisSelector, source, analysis);
    }
  }

  private BranchInputCapture(
      Board board,
      Supplier<BoardHistoryNode> sourceSelector,
      Supplier<BoardHistoryNode> analysisSelector,
      BoardHistoryNode source,
      BoardHistoryNode analysis) {
    this.board = board;
    this.history = board.getHistory();
    this.current = history.getCurrentHistoryNode();
    this.sourceSelector = sourceSelector;
    this.analysisSelector = analysisSelector;
    this.source = source;
    this.analysis = analysis;
    sourceData = source.getData();
    analysisData = analysis.getData();
    revision = board.getContextRevision();
    width = Board.boardWidth;
    height = Board.boardHeight;
    currentTurn = current.getData().blackToPlay;
    sourceTurn = sourceData.blackToPlay;
    analysisTurn = analysisData.blackToPlay;
  }

  Branch.Input capture(
      Supplier<List<String>> variation,
      Supplier<List<String>> visits,
      int maxLength,
      boolean removeDeadChains,
      boolean recordVisits,
      Stone[] retainedStones,
      Boolean retainedTurn) {
    Branch.Position position;
    synchronized (board) {
      if (!matchesContext()) return null;
      Stone[] stones = retainedStones == null ? sourceData.stones : retainedStones;
      if (stones.length != width * height) return null;
      position =
          new Branch.Position(
              width, height, sourceData, stones, retainedTurn == null ? sourceTurn : retainedTurn);
    }
    Branch.Input input;
    // Paired with BoardData.adoptOrdinaryAnalysis / tryToSetBestMoves*FromEngine.
    synchronized (analysisData) {
      List<String> pv = variation.get();
      if (pv == null) return null;
      input =
          new Branch.Input(position, pv, visits.get(), maxLength, removeDeadChains, recordVisits);
    }
    return isCurrent() ? input : null;
  }

  boolean isCurrent() {
    synchronized (board) {
      return matchesContext();
    }
  }

  boolean isEmptyPoint(String coordinate) {
    synchronized (board) {
      if (!matchesContext()) return false;
      return Board.asCoordinates(coordinate)
          .map(coords -> featurecat.lizzie.analysis.AnalysisCandidateValidator.isEmptyPoint(sourceData, coords))
          .orElse(true);
    }
  }

  VariationPreviewGenerator.SourceStones captureCurrentStones(
      VariationPreviewGenerator.Style retained) {
    synchronized (board) {
      if (!matchesContext()) return null;
      return VariationPreviewGenerator.captureSourceStones(current.getData().stones, retained);
    }
  }

  private boolean matchesContext() {
    return Lizzie.board == board
        && board.getHistory() == history
        && history.getCurrentHistoryNode() == current
        && board.getContextRevision() == revision
        && Board.boardWidth == width
        && Board.boardHeight == height
        && sourceSelector.get() == source
        && analysisSelector.get() == analysis
        && source.getData() == sourceData
        && analysis.getData() == analysisData
        && current.getData().blackToPlay == currentTurn
        && sourceData.blackToPlay == sourceTurn
        && analysisData.blackToPlay == analysisTurn;
  }
}
