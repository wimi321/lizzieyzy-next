package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.io.IOException;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * Rules the user picked by hand, applied the way the KataGo rules dialog applies them: saved as the
 * startup rules when asked, the game's imported rules retired, the engine told, and the rules
 * published to the game once the engine reads the same rules back. The rules dialog and the rules
 * caption's menu both go through here.
 */
final class ManualRulesSelection {
  private final Config config;
  private final Supplier<Board> board;
  private final Supplier<LizzieFrame> frame;

  ManualRulesSelection(Config config, Supplier<Board> board, Supplier<LizzieFrame> frame) {
    this.config = config;
    this.board = board;
    this.frame = frame;
  }

  /**
   * Saves whether KataGo starts with these rules. When the config cannot be written this throws and
   * changes nothing.
   */
  void saveStartupRules(KataGoRules requested, boolean autoLoad) throws IOException {
    JSONObject candidateUi = new JSONObject(config.uiConfig.toString());
    candidateUi.put("auto-load-kata-rules", autoLoad);
    if (autoLoad) {
      candidateUi.put("kata-rules", requested.toGtpArgument());
    }
    config.saveConfigSections(candidateUi, config.leelazConfig);
    config.autoLoadKataRules = autoLoad;
    if (autoLoad) {
      config.kataRules = requested.toGtpArgument();
    }
  }

  /**
   * Tells the engine and, once it reads the same rules back, publishes them to the game. {@code
   * lock} keeps the publication apart from the caller's own bookkeeping; {@code onFailure} runs on
   * the completion thread when the engine did not take these rules.
   */
  Leelaz.EngineRulesOperation apply(
      Leelaz engine, KataGoRules requested, Object lock, Runnable onFailure) {
    Board current = board.get();
    current.clearBestMovesAfter(current.getHistory().getStart());
    BoardHistoryList history = current.getHistory();
    BoardHistoryList.ManualRulesIntent manualIntent = history.beginManualRulesIntent();
    BoardHistoryList.SessionRulesTarget previousTarget = manualIntent.previousTarget();
    Object engineIncarnation = engine.engineIncarnationToken();
    long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
    LizzieFrame mainFrame = frame.get();
    if (mainFrame != null) {
      mainFrame.retireImportedRulesForManualSelection(history, previousTarget);
    }
    Leelaz.EngineRulesOperation operation = engine.applyEngineRulesOperation(requested);
    Thread completion =
        new Thread(
            () -> {
              try {
                EngineRulesResult result = operation.await(31_000L);
                boolean taken =
                    result != null
                        && result.isConfirmed()
                        && result.observed() != null
                        && result.observed().semanticallyEquals(requested);
                if (!taken) {
                  onFailure.run();
                  return;
                }
                // The generation is -1 unless the engine was the primary one when it was asked.
                Board now = board.get();
                if (primaryGeneration >= 0
                    && engine.isCurrentEngineIncarnationToken(engineIncarnation)
                    && now != null
                    && Lizzie.capturePrimaryEngineGeneration(engine) == primaryGeneration
                    && now.getHistory() == history) {
                  publish(
                      history, manualIntent, result.observed(), engine, primaryGeneration, lock);
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            "lizzie-set-kata-rules-completion");
    completion.setDaemon(true);
    completion.start();
    return operation;
  }

  private void publish(
      BoardHistoryList history,
      BoardHistoryList.ManualRulesIntent manualIntent,
      KataGoRules observed,
      Leelaz engine,
      long primaryGeneration,
      Object lock) {
    synchronized (lock) {
      BoardHistoryList.SessionRulesTarget published;
      synchronized (history) {
        published = history.publishManualRulesIfCurrent(manualIntent, observed).orElse(null);
      }
      LizzieFrame mainFrame = frame.get();
      if (published != null && mainFrame != null) {
        mainFrame.synchronizeManualRulesAfterSelection(
            history, published, manualIntent, engine, primaryGeneration);
      }
    }
  }
}
