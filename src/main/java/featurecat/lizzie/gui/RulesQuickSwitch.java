package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.json.JSONObject;

/**
 * Switches the rules and the komi together from the rules caption's menu. The komi goes to the
 * engine before the rules, so the game that is re-sent once the engine confirms the rules already
 * has it; in a handicap game the komi is left alone. The choice also becomes the rules new games
 * start with: KataGo's startup rules, the komi a cleared board gets on every engine, and the komi
 * the new-game dialogs offer.
 */
final class RulesQuickSwitch implements RulesQuickMenu.Actions {
  private final Leelaz engine;
  private final Iterable<Leelaz> engines;
  private final GameInfo game;
  private final Config config;
  private final ManualRulesSelection selection;
  private final ResourceBundle strings;
  private final Consumer<String> showMessage;
  private final Runnable openRulesDialog;

  RulesQuickSwitch(
      Leelaz engine,
      Iterable<Leelaz> engines,
      GameInfo game,
      Config config,
      ManualRulesSelection selection,
      ResourceBundle strings,
      Consumer<String> showMessage,
      Runnable openRulesDialog) {
    this.engine = engine;
    this.engines = engines == null ? List.of() : engines;
    this.game = game;
    this.config = config;
    this.selection = selection;
    this.strings = strings;
    this.showMessage = showMessage;
    this.openRulesDialog = openRulesDialog;
  }

  /** The menu for the main window's current engine and game; {@code engines} are all engines. */
  static JPopupMenu menuFor(
      Leelaz engine,
      Iterable<Leelaz> engines,
      Board board,
      Config config,
      LizzieFrame frame,
      ResourceBundle strings,
      Consumer<String> showMessage) {
    GameInfo game = board.getHistory().getGameInfo();
    RulesQuickSwitch actions =
        new RulesQuickSwitch(
            engine,
            engines,
            game,
            config,
            new ManualRulesSelection(config, () -> board, () -> frame),
            strings,
            showMessage,
            frame::setRules);
    KataGoRules current = KataGoRules.parse(engine.recentRulesLine).orElse(null);
    return RulesQuickMenu.build(
        strings,
        RulePreset.of(current, game.getKomi(), RulePreset.chosen(config)),
        game.getHandicap() > 0,
        actions);
  }

  @Override
  public void apply(RulePreset preset) {
    if (engine.isRulesMutationOccupied()) {
      showMessage.accept(strings.getString("AnalysisSettings.reuseStatus.existing_lease"));
      return;
    }
    try {
      rememberForNewGames(preset);
    } catch (IOException failure) {
      showMessage.accept(strings.getString("Lizzie.save.error") + failure.getLocalizedMessage());
      return;
    }
    engine.orikomi = (float) preset.komi();
    for (Leelaz other : engines) {
      if (other != null) other.orikomi = engine.orikomi;
    }
    if (game.getHandicap() <= 0 && game.getKomi() != preset.komi()) {
      engine.komi(preset.komi());
      game.changeKomi();
    }
    selection.apply(
        engine,
        preset.rules(),
        this,
        () ->
            SwingUtilities.invokeLater(
                () -> showMessage.accept(strings.getString("SetKataRules.status.setFailed"))));
  }

  @Override
  public void openRulesDialog() {
    openRulesDialog.run();
  }

  /** Saves the preset as KataGo's startup rules and its komi as the komi of new games. */
  private void rememberForNewGames(RulePreset preset) throws IOException {
    JSONObject newGames =
        new JSONObject()
            .put(RulePreset.CHOSEN_KEY, preset.name())
            .put("new-game-komi", preset.komi())
            .put("new-engine-game-komi", preset.komi());
    selection.saveStartupRules(preset.rules(), true, newGames);
    config.newGameKomi = preset.komi();
    config.newEngineGameKomi = preset.komi();
  }
}
