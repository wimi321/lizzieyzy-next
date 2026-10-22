package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.rules.Movelist;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable plan for one transaction. Frozen when the GamePlan is created. */
public final class EngineGamePlan {
  private final EngineParticipantIdentity black;
  private final EngineParticipantIdentity white;
  private final int blackIndex;
  private final int whiteIndex;
  private final int firstIndex;
  private final int secondIndex;
  private final EngineGamePlayMode playMode;
  private final EngineGameSideLimits blackLimits;
  private final EngineGameSideLimits whiteLimits;
  private final double komi;
  private final int handicap;
  private final List<EngineGameMove> openingMoves;
  private final int openingIndex;
  private final boolean continueGame;
  private final boolean exchangeColors;
  private final int gameOrdinal;
  private final boolean batch;

  private final int batchLimit;
  private final boolean maxMoveLimitEnabled;
  private final int maxMoves;
  private final EngineGameOutputChoices output;
  private final KataGoRules matchRules;
  private final EngineGameOpeningParameters openingParameters;

  EngineGamePlan(
      EngineParticipantIdentity black,
      EngineParticipantIdentity white,
      int blackIndex,
      int whiteIndex,
      int firstIndex,
      int secondIndex,
      EngineGamePlayMode playMode,
      EngineGameSideLimits blackLimits,
      EngineGameSideLimits whiteLimits,
      double komi,
      int handicap,
      List<EngineGameMove> openingMoves,
      int openingIndex,
      boolean continueGame,
      boolean exchangeColors,
      int gameOrdinal,
      boolean batch,
      int batchLimit,
      boolean maxMoveLimitEnabled,
      int maxMoves,
      EngineGameOutputChoices output,
      KataGoRules matchRules,
      EngineGameOpeningParameters openingParameters) {

    this.black = Objects.requireNonNull(black, "black");
    this.white = Objects.requireNonNull(white, "white");
    this.blackIndex = blackIndex;
    this.whiteIndex = whiteIndex;
    this.firstIndex = firstIndex;
    this.secondIndex = secondIndex;
    this.playMode = Objects.requireNonNull(playMode, "playMode");
    this.blackLimits = Objects.requireNonNull(blackLimits, "blackLimits");
    this.whiteLimits = Objects.requireNonNull(whiteLimits, "whiteLimits");
    this.komi = komi;
    this.handicap = handicap;
    this.openingMoves = EngineGameOpeningPlan.copyMoves(openingMoves);
    this.openingIndex = openingIndex;
    this.batch = batch;

    this.continueGame = continueGame;
    this.exchangeColors = exchangeColors;
    this.gameOrdinal = gameOrdinal;
    this.batchLimit = batchLimit;
    this.maxMoveLimitEnabled = maxMoveLimitEnabled;
    this.maxMoves = maxMoves;
    this.output = Objects.requireNonNull(output, "output");
    this.matchRules = Objects.requireNonNull(matchRules, "matchRules");
    this.openingParameters = Objects.requireNonNull(openingParameters, "openingParameters");
  }

  public EngineParticipantIdentity black() {
    return black;
  }

  public EngineParticipantIdentity white() {
    return white;
  }

  public int blackIndex() {
    return blackIndex;
  }

  public int whiteIndex() {
    return whiteIndex;
  }

  public int firstIndex() {
    return firstIndex;
  }

  public int secondIndex() {
    return secondIndex;
  }

  public EngineGamePlayMode playMode() {
    return playMode;
  }

  public EngineGameSideLimits blackLimits() {
    return blackLimits;
  }

  public EngineGameSideLimits whiteLimits() {
    return whiteLimits;
  }

  public double komi() {
    return komi;
  }

  public int handicap() {
    return handicap;
  }

  public List<EngineGameMove> openingMoves() {
    return openingMoves;
  }

  public int openingIndex() {
    return openingIndex;
  }

  public boolean continueGame() {
    return continueGame;
  }

  public boolean exchangeColors() {
    return exchangeColors;
  }

  public int gameOrdinal() {
    return gameOrdinal;
  }

  public int batchLimit() {
    return batchLimit;
  }

  public boolean batch() {
    return batch;
  }

  public boolean maxMoveLimitEnabled() {
    return maxMoveLimitEnabled;
  }

  public int maxMoves() {
    return maxMoves;
  }

  public EngineGameOutputChoices output() {
    return output;
  }

  public KataGoRules matchRules() {
    return matchRules;
  }

  /** The batch's frozen opening settings, by participant position rather than color. */
  public EngineGameOpeningParameters openingParameters() {
    return openingParameters;
  }

  public boolean genmove() {
    return playMode == EngineGamePlayMode.GENMOVE;
  }

  public boolean firstIsBlack() {
    return firstIndex == blackIndex;
  }

  public int resolvedMaxMoves() {
    if (maxMoveLimitEnabled && maxMoves > 0) {
      return maxMoves;
    }
    return featurecat.lizzie.rules.Board.boardWidth * featurecat.lizzie.rules.Board.boardHeight * 2;
  }

  public ArrayList<Movelist> openingMovelist() {
    if (openingMoves == null || openingMoves.isEmpty()) {
      return null;
    }
    ArrayList<Movelist> copied = new ArrayList<>(openingMoves.size());
    for (EngineGameMove move : openingMoves) {
      if (move == null) {
        continue;
      }
      Movelist listed = new Movelist();
      listed.x = move.x();
      listed.y = move.y();
      listed.movenum = move.moveNumber();
      listed.isblack = move.black();
      listed.ispass = move.pass();
      copied.add(listed);
    }
    return copied.isEmpty() ? null : copied;
  }

  EngineGameView view() {
    return new EngineGameView(
        black,
        white,
        playMode,
        gameOrdinal,
        blackIndex,
        whiteIndex,
        firstIndex,
        secondIndex,
        batch,
        openingIndex);
  }
}
