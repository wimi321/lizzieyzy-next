package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * Switches the rules and the komi together from the rules caption's menu. The komi goes to the
 * engine before the rules, so the game that is re-sent once the engine confirms the rules already
 * has it; in a handicap game the komi is left alone. When KataGo is set to start with the chosen
 * rules, the choice becomes those startup rules, as it does in the rules dialog.
 */
final class RulesQuickSwitch implements RulesQuickMenu.Actions {
  private final Leelaz engine;
  private final GameInfo game;
  private final Config config;
  private final ManualRulesSelection selection;
  private final ResourceBundle strings;
  private final Consumer<String> showMessage;
  private final Runnable openRulesDialog;

  RulesQuickSwitch(
      Leelaz engine,
      GameInfo game,
      Config config,
      ManualRulesSelection selection,
      ResourceBundle strings,
      Consumer<String> showMessage,
      Runnable openRulesDialog) {
    this.engine = engine;
    this.game = game;
    this.config = config;
    this.selection = selection;
    this.strings = strings;
    this.showMessage = showMessage;
    this.openRulesDialog = openRulesDialog;
  }

  /** The menu for the main window's current engine and game. */
  static JPopupMenu menuFor(
      Leelaz engine,
      Board board,
      Config config,
      LizzieFrame frame,
      ResourceBundle strings,
      Consumer<String> showMessage) {
    GameInfo game = board.getHistory().getGameInfo();
    RulesQuickSwitch actions =
        new RulesQuickSwitch(
            engine,
            game,
            config,
            new ManualRulesSelection(config, () -> board, () -> frame),
            strings,
            showMessage,
            frame::setRules);
    return RulesQuickMenu.build(
        strings,
        RulePreset.of(engine.rulesSummary, game.getKomi()),
        game.getHandicap() > 0,
        actions);
  }

  @Override
  public void apply(RulePreset preset) {
    if (engine.isRulesMutationOccupied()) {
      showMessage.accept(strings.getString("AnalysisSettings.reuseStatus.existing_lease"));
      return;
    }
    KataGoRules rules = preset.rules();
    if (config.autoLoadKataRules) {
      try {
        selection.saveStartupRules(rules, true);
      } catch (IOException failure) {
        showMessage.accept(strings.getString("Lizzie.save.error") + failure.getLocalizedMessage());
        return;
      }
    }
    if (game.getHandicap() <= 0 && game.getKomi() != preset.komi()) {
      engine.komi(preset.komi());
      game.changeKomi();
    }
    selection.apply(
        engine,
        rules,
        this,
        () ->
            SwingUtilities.invokeLater(
                () -> showMessage.accept(strings.getString("SetKataRules.status.setFailed"))));
  }

  @Override
  public void openRulesDialog() {
    openRulesDialog.run();
  }
}
