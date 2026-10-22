package featurecat.lizzie.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Why a confirmed engine-game participant command failed, as shown to the user. Engine responses
 * and GTP command lines are only ever arguments, never translated.
 */
final class EngineGameCommandFailure {
  private static final Logger LOG = LoggerFactory.getLogger(EngineGameCommandFailure.class);

  /** The GTP command a stop fence sends. */
  static final String FENCE = "name";

  private EngineGameCommandFailure() {}

  static String timeout() {
    return EngineGameParameterSession.text("EngineGameCommand.timeout");
  }

  /** The command could not be queued or written; {@code command} null means the stop fence. */
  static String notSent(String command) {
    return EngineGameParameterSession.text(
        "EngineGameCommand.notSent", command == null ? FENCE : command);
  }

  /** As {@link #notSent(String)}, keeping the send failure itself in the log. */
  static String notSent(String command, RuntimeException cause) {
    LOG.warn("Engine-game command {} could not be sent", command == null ? FENCE : command, cause);
    return notSent(command);
  }

  static String rejected(String command, String response) {
    return EngineGameParameterSession.text("EngineGameCommand.rejected", command, response);
  }

  static String readerChanged() {
    return EngineGameParameterSession.text("EngineGameCommand.readerChanged");
  }

  static String transactionLost() {
    return EngineGameParameterSession.text("EngineGameParameters.transactionLost");
  }

  static String positionFailed() {
    return EngineGameParameterSession.text("EngineGameCommand.positionFailed");
  }
}
