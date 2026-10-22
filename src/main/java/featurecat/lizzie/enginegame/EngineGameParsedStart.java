package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;
import java.util.Objects;

/** Parsed engine-game dialog/toolbar values before they become {@link EngineGameBatchSpec}. */
public final class EngineGameParsedStart {
  private final EngineParticipantIdentity first;
  private final EngineParticipantIdentity second;
  private final boolean genmove;
  private final EngineGameTimeMode firstTimeMode;
  private final EngineGameTimeMode secondTimeMode;
  private final String firstAdvancedTimeCommand;
  private final String secondAdvancedTimeCommand;
  private final boolean timeLimitEnabled;
  private final int firstTimeSeconds;
  private final int secondTimeSeconds;
  private final boolean visitLimitEnabled;
  private final int firstVisits;
  private final int secondVisits;
  private final boolean firstMoveVisitLimitEnabled;
  private final int firstOpeningVisits;
  private final int secondOpeningVisits;
  private final EngineGameResignPolicy firstResign;
  private final EngineGameResignPolicy secondResign;
  private final double komi;
  private final int handicap;
  private final boolean continueGame;
  private final List<EngineGameMove> continueMoves;
  private final boolean sgfOpening;
  private final List<List<EngineGameMove>> sgfOpenings;
  private final boolean sgfRandom;
  private final boolean exchangeColors;
  private final boolean batch;
  private final int batchLimit;
  private final boolean maxMoveLimitEnabled;
  private final int maxMoves;
  private final boolean autosave;
  private final boolean saveWinrateImage;
  private final String batchName;
  private final KataGoRules matchRules;
  private final EngineGameOpeningParameters openingParameters;

  private EngineGameParsedStart(Builder builder) {
    this.first = Objects.requireNonNull(builder.first, "first");
    this.second = Objects.requireNonNull(builder.second, "second");
    this.genmove = builder.genmove;
    this.firstTimeMode =
        builder.firstTimeMode == null ? EngineGameTimeMode.FIXED : builder.firstTimeMode;
    this.secondTimeMode =
        builder.secondTimeMode == null ? EngineGameTimeMode.FIXED : builder.secondTimeMode;
    this.firstAdvancedTimeCommand =
        builder.firstAdvancedTimeCommand == null ? "" : builder.firstAdvancedTimeCommand;
    this.secondAdvancedTimeCommand =
        builder.secondAdvancedTimeCommand == null ? "" : builder.secondAdvancedTimeCommand;
    this.timeLimitEnabled = builder.timeLimitEnabled;
    this.firstTimeSeconds = builder.firstTimeSeconds;
    this.secondTimeSeconds = builder.secondTimeSeconds;
    this.visitLimitEnabled = builder.visitLimitEnabled;
    this.firstVisits = builder.firstVisits;
    this.secondVisits = builder.secondVisits;
    this.firstMoveVisitLimitEnabled = builder.firstMoveVisitLimitEnabled;
    this.firstOpeningVisits = builder.firstOpeningVisits;
    this.secondOpeningVisits = builder.secondOpeningVisits;
    this.firstResign =
        builder.firstResign == null ? EngineGameResignPolicy.defaults() : builder.firstResign;
    this.secondResign =
        builder.secondResign == null ? EngineGameResignPolicy.defaults() : builder.secondResign;
    this.komi = builder.komi;
    this.handicap = builder.handicap;
    this.continueGame = builder.continueGame;
    this.continueMoves = EngineGameOpeningPlan.copyMoves(builder.continueMoves);
    this.sgfOpening = builder.sgfOpening;
    this.sgfOpenings = EngineGameOpeningPlan.copyCatalog(builder.sgfOpenings);
    this.sgfRandom = builder.sgfRandom;
    this.exchangeColors = builder.exchangeColors;
    this.batch = builder.batch;
    this.batchLimit = builder.batchLimit;
    this.maxMoveLimitEnabled = builder.maxMoveLimitEnabled;
    this.maxMoves = builder.maxMoves;
    this.autosave = builder.autosave;
    this.saveWinrateImage = builder.saveWinrateImage;
    this.batchName = builder.batchName == null ? "" : builder.batchName;
    this.matchRules =
        builder.matchRules == null
            ? KataGoRules.parse("chinese").orElseThrow()
            : builder.matchRules;
    this.openingParameters =
        builder.openingParameters == null
            ? EngineGameOpeningParameters.NEUTRAL
            : builder.openingParameters;
  }

  public static Builder builder() {
    return new Builder();
  }

  public EngineParticipantIdentity first() {
    return first;
  }

  public EngineParticipantIdentity second() {
    return second;
  }

  public boolean genmove() {
    return genmove;
  }

  public EngineGameTimeMode firstTimeMode() {
    return firstTimeMode;
  }

  public EngineGameTimeMode secondTimeMode() {
    return secondTimeMode;
  }

  public String firstAdvancedTimeCommand() {
    return firstAdvancedTimeCommand;
  }

  public String secondAdvancedTimeCommand() {
    return secondAdvancedTimeCommand;
  }

  public boolean timeLimitEnabled() {
    return timeLimitEnabled;
  }

  public int firstTimeSeconds() {
    return firstTimeSeconds;
  }

  public int secondTimeSeconds() {
    return secondTimeSeconds;
  }

  public boolean visitLimitEnabled() {
    return visitLimitEnabled;
  }

  public int firstVisits() {
    return firstVisits;
  }

  public int secondVisits() {
    return secondVisits;
  }

  public boolean firstMoveVisitLimitEnabled() {
    return firstMoveVisitLimitEnabled;
  }

  public int firstOpeningVisits() {
    return firstOpeningVisits;
  }

  public int secondOpeningVisits() {
    return secondOpeningVisits;
  }

  public EngineGameResignPolicy firstResign() {
    return firstResign;
  }

  public EngineGameResignPolicy secondResign() {
    return secondResign;
  }

  public double komi() {
    return komi;
  }

  public int handicap() {
    return handicap;
  }

  public boolean continueGame() {
    return continueGame;
  }

  public List<EngineGameMove> continueMoves() {
    return continueMoves;
  }

  public boolean sgfOpening() {
    return sgfOpening;
  }

  public List<List<EngineGameMove>> sgfOpenings() {
    return sgfOpenings;
  }

  public boolean sgfRandom() {
    return sgfRandom;
  }

  public boolean exchangeColors() {
    return exchangeColors;
  }

  public boolean batch() {
    return batch;
  }

  public int batchLimit() {
    return batchLimit;
  }

  public boolean maxMoveLimitEnabled() {
    return maxMoveLimitEnabled;
  }

  public int maxMoves() {
    return maxMoves;
  }

  public boolean autosave() {
    return autosave;
  }

  public boolean saveWinrateImage() {
    return saveWinrateImage;
  }

  public String batchName() {
    return batchName;
  }

  public KataGoRules matchRules() {
    return matchRules;
  }

  public EngineGameOpeningParameters openingParameters() {
    return openingParameters;
  }

  public static final class Builder {
    private EngineParticipantIdentity first;
    private EngineParticipantIdentity second;
    private boolean genmove;
    private EngineGameTimeMode firstTimeMode = EngineGameTimeMode.FIXED;
    private EngineGameTimeMode secondTimeMode = EngineGameTimeMode.FIXED;
    private String firstAdvancedTimeCommand = "";
    private String secondAdvancedTimeCommand = "";
    private boolean timeLimitEnabled;
    private int firstTimeSeconds = -1;
    private int secondTimeSeconds = -1;
    private boolean visitLimitEnabled;
    private int firstVisits = -1;
    private int secondVisits = -1;
    private boolean firstMoveVisitLimitEnabled;
    private int firstOpeningVisits = -1;
    private int secondOpeningVisits = -1;
    private EngineGameResignPolicy firstResign = EngineGameResignPolicy.defaults();
    private EngineGameResignPolicy secondResign = EngineGameResignPolicy.defaults();
    private double komi = 7.5;
    private int handicap;
    private boolean continueGame;
    private List<EngineGameMove> continueMoves = List.of();
    private boolean sgfOpening;
    private List<List<EngineGameMove>> sgfOpenings = List.of();
    private boolean sgfRandom;
    private boolean exchangeColors;
    private boolean batch;
    private int batchLimit = 1;
    private boolean maxMoveLimitEnabled;
    private int maxMoves = 450;
    private boolean autosave = true;
    private boolean saveWinrateImage;
    private String batchName = "";
    private KataGoRules matchRules = KataGoRules.parse("chinese").orElseThrow();
    private EngineGameOpeningParameters openingParameters = EngineGameOpeningParameters.NEUTRAL;

    public Builder first(EngineParticipantIdentity first) {
      this.first = first;
      return this;
    }

    public Builder second(EngineParticipantIdentity second) {
      this.second = second;
      return this;
    }

    public Builder genmove(boolean genmove) {
      this.genmove = genmove;
      return this;
    }

    public Builder firstTimeMode(EngineGameTimeMode firstTimeMode) {
      this.firstTimeMode = firstTimeMode;
      return this;
    }

    public Builder secondTimeMode(EngineGameTimeMode secondTimeMode) {
      this.secondTimeMode = secondTimeMode;
      return this;
    }

    public Builder firstAdvancedTimeCommand(String firstAdvancedTimeCommand) {
      this.firstAdvancedTimeCommand = firstAdvancedTimeCommand;
      return this;
    }

    public Builder secondAdvancedTimeCommand(String secondAdvancedTimeCommand) {
      this.secondAdvancedTimeCommand = secondAdvancedTimeCommand;
      return this;
    }

    public Builder timeLimitEnabled(boolean timeLimitEnabled) {
      this.timeLimitEnabled = timeLimitEnabled;
      return this;
    }

    public Builder firstTimeSeconds(int firstTimeSeconds) {
      this.firstTimeSeconds = firstTimeSeconds;
      return this;
    }

    public Builder secondTimeSeconds(int secondTimeSeconds) {
      this.secondTimeSeconds = secondTimeSeconds;
      return this;
    }

    public Builder visitLimitEnabled(boolean visitLimitEnabled) {
      this.visitLimitEnabled = visitLimitEnabled;
      return this;
    }

    public Builder firstVisits(int firstVisits) {
      this.firstVisits = firstVisits;
      return this;
    }

    public Builder secondVisits(int secondVisits) {
      this.secondVisits = secondVisits;
      return this;
    }

    public Builder firstMoveVisitLimitEnabled(boolean firstMoveVisitLimitEnabled) {
      this.firstMoveVisitLimitEnabled = firstMoveVisitLimitEnabled;
      return this;
    }

    public Builder firstOpeningVisits(int firstOpeningVisits) {
      this.firstOpeningVisits = firstOpeningVisits;
      return this;
    }

    public Builder secondOpeningVisits(int secondOpeningVisits) {
      this.secondOpeningVisits = secondOpeningVisits;
      return this;
    }

    public Builder firstResign(EngineGameResignPolicy firstResign) {
      this.firstResign = firstResign;
      return this;
    }

    public Builder secondResign(EngineGameResignPolicy secondResign) {
      this.secondResign = secondResign;
      return this;
    }

    public Builder komi(double komi) {
      this.komi = komi;
      return this;
    }

    public Builder handicap(int handicap) {
      this.handicap = handicap;
      return this;
    }

    public Builder continueGame(boolean continueGame) {
      this.continueGame = continueGame;
      return this;
    }

    public Builder continueMoves(List<EngineGameMove> continueMoves) {
      this.continueMoves = continueMoves;
      return this;
    }

    public Builder sgfOpening(boolean sgfOpening) {
      this.sgfOpening = sgfOpening;
      return this;
    }

    public Builder sgfOpenings(List<List<EngineGameMove>> sgfOpenings) {
      this.sgfOpenings = sgfOpenings;
      return this;
    }

    public Builder sgfRandom(boolean sgfRandom) {
      this.sgfRandom = sgfRandom;
      return this;
    }

    public Builder exchangeColors(boolean exchangeColors) {
      this.exchangeColors = exchangeColors;
      return this;
    }

    public Builder batch(boolean batch) {
      this.batch = batch;
      return this;
    }

    public Builder batchLimit(int batchLimit) {
      this.batchLimit = batchLimit;
      return this;
    }

    public Builder maxMoveLimitEnabled(boolean maxMoveLimitEnabled) {
      this.maxMoveLimitEnabled = maxMoveLimitEnabled;
      return this;
    }

    public Builder maxMoves(int maxMoves) {
      this.maxMoves = maxMoves;
      return this;
    }

    public Builder autosave(boolean autosave) {
      this.autosave = autosave;
      return this;
    }

    public Builder saveWinrateImage(boolean saveWinrateImage) {
      this.saveWinrateImage = saveWinrateImage;
      return this;
    }

    public Builder batchName(String batchName) {
      this.batchName = batchName;
      return this;
    }

    public Builder matchRules(KataGoRules matchRules) {
      this.matchRules = matchRules;
      return this;
    }

    public Builder openingParameters(EngineGameOpeningParameters openingParameters) {
      this.openingParameters = openingParameters;
      return this;
    }

    public EngineGameParsedStart build() {
      return new EngineGameParsedStart(this);
    }
  }
}
