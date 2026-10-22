package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameMatchRulesSelection;
import featurecat.lizzie.enginegame.EngineGameMove;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGameResignPolicy;
import featurecat.lizzie.enginegame.EngineGameTimeMode;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.util.Utils;
import java.util.ArrayList;
import java.util.List;

/** Captures parsed toolbar/dialog product values without putting Swing types into the Spec. */
final class EngineGameBatchSpecCapture {
  private EngineGameBatchSpecCapture() {}

  static EngineGameParsedStart fromToolbar(BottomToolbar toolbar, List<EngineData> engines) {
    Config config = Lizzie.config;
    boolean continueGame = toolbar.chkenginePkContinue.isSelected();
    boolean sgfOpening = config != null && config.chkEngineSgfStart;
    return EngineGameParsedStart.builder()
        .first(identity(engines, toolbar.engineBlackToolbar))
        .second(identity(engines, toolbar.engineWhiteToolbar))
        .genmove(toolbar.isGenmoveToolbar)
        .firstTimeMode(timeMode(true))
        .secondTimeMode(timeMode(false))
        .firstAdvancedTimeCommand(advancedCommand(true))
        .secondAdvancedTimeCommand(advancedCommand(false))
        .timeLimitEnabled(toolbar.chkenginePkTime.isSelected())
        .firstTimeSeconds(Utils.parseTextToInt(toolbar.txtenginePkTime, -1))
        .secondTimeSeconds(Utils.parseTextToInt(toolbar.txtenginePkTimeWhite, -1))
        .visitLimitEnabled(toolbar.chkenginePkPlayouts.isSelected())
        .firstVisits(Utils.parseTextToInt(toolbar.txtenginePkPlayputs, -1))
        .secondVisits(Utils.parseTextToInt(toolbar.txtenginePkPlayputsWhite, -1))
        .firstMoveVisitLimitEnabled(toolbar.chkenginePkFirstPlayputs.isSelected())
        .firstOpeningVisits(Utils.parseTextToInt(toolbar.txtenginePkFirstPlayputs, -1))
        .secondOpeningVisits(Utils.parseTextToInt(toolbar.txtenginePkFirstPlayputsWhite, -1))
        .firstResign(resign(true))
        .secondResign(resign(false))
        .komi(config == null || config.newEngineGameKomi == null ? 7.5 : config.newEngineGameKomi)
        .handicap(config == null ? 0 : config.newEngineGameHandicap)
        .continueGame(continueGame)
        .continueMoves(continueMoves(continueGame))
        .sgfOpening(sgfOpening)
        .sgfOpenings(sgfOpenings(sgfOpening))
        .sgfRandom(config != null && config.engineSgfStartRandom)
        .exchangeColors(toolbar.exChangeToolbar)
        .batch(toolbar.chkenginePkBatch.isSelected())
        .batchLimit(Utils.parseTextToInt(toolbar.txtenginePkBatch, 1))
        .maxMoveLimitEnabled(toolbar.checkGameMaxMove)
        .maxMoves(toolbar.maxGameMoves)
        .autosave(toolbar.AutosavePk)
        .saveWinrateImage(toolbar.enginePkSaveWinrate)
        .batchName(toolbar.batchPkNameToolbar)
        .matchRules(
            EngineGameMatchRulesSelection.stored(config)
                .orElseGet(() -> EngineGameMatchRulesSelection.prefill(config)))
        .openingParameters(EngineGameOpeningParameters.stored(config))
        .build();
  }

  private static EngineParticipantIdentity identity(List<EngineData> engines, int index) {
    if (engines == null || index < 0 || index >= engines.size()) {
      return new EngineParticipantIdentity("", "");
    }
    EngineData engine = engines.get(index);
    if (engine == null) {
      return new EngineParticipantIdentity("", "");
    }
    return new EngineParticipantIdentity(engine.commands, engine.name);
  }

  private static EngineGameTimeMode timeMode(boolean black) {
    if (Lizzie.config == null) {
      return EngineGameTimeMode.FIXED;
    }
    return switch (DesktopTimeControl.loadEngineGameSideMode(Lizzie.config, black)) {
      case ENGINE_OWNED -> EngineGameTimeMode.ENGINE_OWNED;
      case RAW_ADVANCED -> EngineGameTimeMode.RAW_ADVANCED;
      case FIXED -> EngineGameTimeMode.FIXED;
    };
  }

  private static String advancedCommand(boolean black) {
    if (Lizzie.config == null) {
      return "";
    }
    String command = black ? Lizzie.config.advanceBlackTimeTxt : Lizzie.config.advanceWhiteTimeTxt;
    return command == null ? "" : command;
  }

  private static EngineGameResignPolicy resign(boolean first) {
    Config config = Lizzie.config;
    if (config == null) {
      return EngineGameResignPolicy.defaults();
    }
    if (first) {
      return new EngineGameResignPolicy(
          config.firstEngineMinMove,
          config.firstEngineResignMoveCounts,
          config.firstEngineResignWinrate);
    }
    return new EngineGameResignPolicy(
        config.secondEngineMinMove,
        config.secondEngineResignMoveCounts,
        config.secondEngineResignWinrate);
  }

  private static List<EngineGameMove> continueMoves(boolean continueGame) {
    if (!continueGame || Lizzie.board == null) {
      return List.of();
    }
    return toMoves(Lizzie.board.getMoveList());
  }

  private static List<List<EngineGameMove>> sgfOpenings(boolean sgfOpening) {
    if (!sgfOpening || Lizzie.frame == null || Lizzie.frame.enginePKSgfString == null) {
      return List.of();
    }
    List<List<EngineGameMove>> openings = new ArrayList<>();
    for (ArrayList<Movelist> opening : Lizzie.frame.enginePKSgfString) {
      openings.add(toMoves(opening));
    }
    return openings;
  }

  private static List<EngineGameMove> toMoves(List<Movelist> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    List<EngineGameMove> moves = new ArrayList<>(source.size());
    for (Movelist move : source) {
      if (move == null) {
        continue;
      }
      moves.add(new EngineGameMove(move.x, move.y, move.movenum, move.isblack, move.ispass));
    }
    return moves;
  }
}
