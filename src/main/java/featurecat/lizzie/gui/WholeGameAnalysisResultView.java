package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;

/**
 * Session-scoped display policy for cached whole-game analysis results.
 *
 * <p>The policy never mutates the user's persistent candidate, branch, or move-rank settings. It
 * only overrides their effective rendering while the analyzed game remains open and the displayed
 * node has the cached payload needed by that rendering operation. Transient policy and heatmap
 * modes are reset once when this view is activated, then remain under the user's control.
 */
final class WholeGameAnalysisResultView {
  private BoardHistoryNode analyzedRoot;

  void activate(BoardHistoryNode root) {
    analyzedRoot = root;
  }

  boolean isActiveForGame(BoardHistoryNode currentRoot) {
    return analyzedRoot != null && analyzedRoot == currentRoot;
  }

  boolean hasVisibleSuggestions(BoardHistoryNode currentRoot, BoardHistoryNode displayedNode) {
    return isActiveForGame(currentRoot) && hasCachedSuggestions(displayedNode);
  }

  boolean hasVisibleMoveEvaluation(BoardHistoryNode currentRoot, BoardHistoryNode displayedNode) {
    return isActiveForGame(currentRoot) && hasCachedMoveEvaluation(displayedNode);
  }

  boolean shouldShowSuggestions(
      BoardHistoryNode currentRoot, BoardHistoryNode displayedNode, boolean configuredValue) {
    return configuredValue || hasVisibleSuggestions(currentRoot, displayedNode);
  }

  boolean shouldShowSuggestionWinrate(
      BoardHistoryNode currentRoot, BoardHistoryNode displayedNode, boolean configuredValue) {
    return configuredValue || hasVisibleSuggestions(currentRoot, displayedNode);
  }

  boolean shouldShowSuggestionPlayouts(
      BoardHistoryNode currentRoot, BoardHistoryNode displayedNode, boolean configuredValue) {
    return configuredValue || hasVisibleSuggestions(currentRoot, displayedNode);
  }

  int effectiveMoveRankLimit(
      BoardHistoryNode currentRoot, BoardHistoryNode displayedNode, int configuredLimit) {
    if (configuredLimit < 0 && hasVisibleMoveEvaluation(currentRoot, displayedNode)) {
      return 1;
    }
    return configuredLimit;
  }

  static boolean hasCachedSuggestions(BoardHistoryNode node) {
    if (node == null) {
      return false;
    }
    BoardData data = node.getData();
    return data != null && data.hasCompletePrimaryAnalysis(1, false);
  }

  static boolean hasCachedMoveEvaluation(BoardHistoryNode node) {
    if (node == null || !node.previous().isPresent()) {
      return false;
    }
    BoardData current = node.getData();
    BoardData previous = node.previous().get().getData();
    return hasCachedSuggestions(node)
        && hasCachedSuggestions(node.previous().get())
        && current.getPlayouts() > 0
        && previous.getPlayouts() > 0;
  }
}
