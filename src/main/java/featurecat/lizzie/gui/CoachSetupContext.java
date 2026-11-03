package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.BoardHistoryList;

/**
 * What the AI Coach setup takes from the main window: whether its board has a position to start
 * from, and the komi of an even game under its current rules.
 */
record CoachSetupContext(boolean fromCurrentAvailable, double evenKomi) {
  static CoachSetupContext ofMainWindow() {
    BoardHistoryList history = Lizzie.board.getHistory();
    GameInfo game = history.getGameInfo();
    return new CoachSetupContext(
        history.getMoveNumber() > 0,
        RulePreset.evenGameKomi(Lizzie.config, game.getKomi(), game.getHandicap() > 0));
  }
}
