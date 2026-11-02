package featurecat.lizzie.training;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Immutable configuration for one AI coaching game. */
public final class HumanSlTrainingConfig {
  public enum PlayerColor {
    RANDOM,
    BLACK,
    WHITE
  }

  public final TrainingMode mode;
  public final OpponentPreset opponentPreset;
  public final CoachLevel level;
  public final PlayerColor playerColor;
  public final HumanThinkTime.Pace pace;

  /** The AI's search budget per move. The setup dialog keeps it at 10 seconds for every pace. */
  public final int moveTimeSeconds;

  public final int handicap;
  public final double komi;
  public final boolean fromCurrentPosition;

  private HumanSlTrainingConfig(Builder builder) {
    mode = Objects.requireNonNull(builder.mode, "mode");
    opponentPreset = Objects.requireNonNull(builder.opponentPreset, "opponentPreset");
    level = Objects.requireNonNull(builder.level, "level");
    playerColor = Objects.requireNonNull(builder.playerColor, "playerColor");
    pace = Objects.requireNonNull(builder.pace, "pace");
    moveTimeSeconds = Math.max(2, builder.moveTimeSeconds);
    handicap = Math.max(0, Math.min(9, builder.handicap));
    komi = builder.komi;
    fromCurrentPosition = builder.fromCurrentPosition;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String humanSlProfile() {
    return opponentPreset == OpponentPreset.RANK
        ? level.play().profile()
        : opponentPreset.profileFor(level.rank(), level.dan());
  }

  /** How the AI plays its moves; empty for the retired presets, which still pick by profile. */
  public Optional<HumanSlPlaySettings> play() {
    return opponentPreset == OpponentPreset.RANK ? Optional.of(level.play()) : Optional.empty();
  }

  public int analysisVisits() {
    return opponentPreset.recommendedVisits();
  }

  public int rootSymmetries() {
    return opponentPreset.rootSymmetries();
  }

  /** With a handicap the player takes Black and receives it; otherwise as chosen. */
  public boolean resolveHumanIsBlack() {
    if (handicap >= 1 || playerColor == PlayerColor.BLACK) {
      return true;
    }
    if (playerColor == PlayerColor.WHITE) {
      return false;
    }
    return ThreadLocalRandom.current().nextBoolean();
  }

  public static final class Builder {
    private TrainingMode mode = TrainingMode.LIVE_FEEDBACK;
    private OpponentPreset opponentPreset = OpponentPreset.RANK;
    private CoachLevel level = CoachLevel.DEFAULT;
    private PlayerColor playerColor = PlayerColor.RANDOM;
    private HumanThinkTime.Pace pace = HumanThinkTime.Pace.NATURAL;
    private int moveTimeSeconds = 10;
    private int handicap;
    private double komi = 7.5;
    private boolean fromCurrentPosition;

    public Builder mode(TrainingMode value) {
      mode = value;
      return this;
    }

    public Builder opponentPreset(OpponentPreset value) {
      opponentPreset = value;
      return this;
    }

    public Builder level(CoachLevel value) {
      level = value;
      return this;
    }

    /** The plain level of that number, as older settings and callers name it. */
    public Builder rank(int value, boolean isDan) {
      level = CoachLevel.forRank(value, isDan);
      return this;
    }

    public Builder playerColor(PlayerColor value) {
      playerColor = value;
      return this;
    }

    public Builder pace(HumanThinkTime.Pace value) {
      pace = value;
      return this;
    }

    public Builder moveTimeSeconds(int value) {
      moveTimeSeconds = value;
      return this;
    }

    public Builder handicap(int value) {
      handicap = value;
      return this;
    }

    public Builder komi(double value) {
      komi = value;
      return this;
    }

    public Builder fromCurrentPosition(boolean value) {
      fromCurrentPosition = value;
      return this;
    }

    public HumanSlTrainingConfig build() {
      return new HumanSlTrainingConfig(this);
    }
  }
}
