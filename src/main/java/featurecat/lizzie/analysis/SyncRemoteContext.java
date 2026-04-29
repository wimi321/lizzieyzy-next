package featurecat.lizzie.analysis;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

final class SyncRemoteContext {
  enum SyncPlatform {
    FOX,
    YIKE,
    GENERIC
  }

  enum WindowKind {
    LIVE_ROOM,
    RECORD_VIEW,
    UNKNOWN
  }

  final SyncPlatform platform;
  final WindowKind windowKind;
  final OptionalInt foxMoveNumber;
  final Optional<String> roomToken;
  final OptionalInt liveTitleMove;
  final OptionalInt recordCurrentMove;
  final OptionalInt recordTotalMove;
  final boolean recordAtEnd;
  final Optional<String> titleFingerprint;
  final boolean forceRebuild;

  private SyncRemoteContext(
      SyncPlatform platform,
      WindowKind windowKind,
      OptionalInt foxMoveNumber,
      Optional<String> roomToken,
      OptionalInt liveTitleMove,
      OptionalInt recordCurrentMove,
      OptionalInt recordTotalMove,
      boolean recordAtEnd,
      Optional<String> titleFingerprint,
      boolean forceRebuild) {
    this.platform = platform;
    this.windowKind = windowKind;
    this.foxMoveNumber = foxMoveNumber;
    this.roomToken = normalizeText(roomToken);
    this.liveTitleMove = liveTitleMove;
    this.recordCurrentMove = recordCurrentMove;
    this.recordTotalMove = recordTotalMove;
    this.recordAtEnd = recordAtEnd;
    this.titleFingerprint = normalizeText(titleFingerprint);
    this.forceRebuild = forceRebuild;
  }

  static SyncRemoteContext generic(boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.GENERIC,
        WindowKind.UNKNOWN,
        OptionalInt.empty(),
        Optional.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  static SyncRemoteContext foxUnknown(boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.FOX,
        WindowKind.UNKNOWN,
        OptionalInt.empty(),
        Optional.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  static SyncRemoteContext forFoxLive(
      OptionalInt foxMoveNumber,
      String roomToken,
      OptionalInt liveTitleMove,
      boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.FOX,
        WindowKind.LIVE_ROOM,
        foxMoveNumber,
        Optional.ofNullable(roomToken),
        liveTitleMove,
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  static SyncRemoteContext forFoxRecord(
      OptionalInt foxMoveNumber,
      OptionalInt recordCurrentMove,
      OptionalInt recordTotalMove,
      boolean recordAtEnd,
      String titleFingerprint,
      boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.FOX,
        WindowKind.RECORD_VIEW,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        recordCurrentMove,
        recordTotalMove,
        recordAtEnd,
        Optional.ofNullable(titleFingerprint),
        forceRebuild);
  }

  SyncRemoteContext withPlatform(SyncPlatform nextPlatform) {
    if (nextPlatform == SyncPlatform.GENERIC) {
      return generic(forceRebuild);
    }
    return new SyncRemoteContext(
        nextPlatform,
        WindowKind.UNKNOWN,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  SyncRemoteContext withFoxMoveNumber(OptionalInt nextFoxMoveNumber) {
    return new SyncRemoteContext(
        platform,
        windowKind,
        nextFoxMoveNumber,
        roomToken,
        liveTitleMove,
        recordCurrentMove,
        recordTotalMove,
        recordAtEnd,
        titleFingerprint,
        forceRebuild);
  }

  SyncRemoteContext withRoomToken(String nextRoomToken) {
    return new SyncRemoteContext(
        platform,
        WindowKind.LIVE_ROOM,
        foxMoveNumber,
        Optional.ofNullable(nextRoomToken),
        liveTitleMove,
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  SyncRemoteContext withLiveTitleMove(OptionalInt nextLiveTitleMove) {
    return new SyncRemoteContext(
        platform,
        WindowKind.LIVE_ROOM,
        foxMoveNumber,
        roomToken,
        nextLiveTitleMove,
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  SyncRemoteContext withRecordCurrentMove(OptionalInt nextRecordCurrentMove) {
    return new SyncRemoteContext(
        platform,
        WindowKind.RECORD_VIEW,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        nextRecordCurrentMove,
        recordTotalMove,
        recordAtEnd,
        titleFingerprint,
        forceRebuild);
  }

  SyncRemoteContext withRecordTotalMove(OptionalInt nextRecordTotalMove) {
    return new SyncRemoteContext(
        platform,
        WindowKind.RECORD_VIEW,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        recordCurrentMove,
        nextRecordTotalMove,
        recordAtEnd,
        titleFingerprint,
        forceRebuild);
  }

  SyncRemoteContext withRecordAtEnd(boolean nextRecordAtEnd) {
    return new SyncRemoteContext(
        platform,
        WindowKind.RECORD_VIEW,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        recordCurrentMove,
        recordTotalMove,
        nextRecordAtEnd,
        titleFingerprint,
        forceRebuild);
  }

  SyncRemoteContext withTitleFingerprint(String nextTitleFingerprint) {
    return new SyncRemoteContext(
        platform,
        WindowKind.RECORD_VIEW,
        foxMoveNumber,
        Optional.empty(),
        OptionalInt.empty(),
        recordCurrentMove,
        recordTotalMove,
        recordAtEnd,
        Optional.ofNullable(nextTitleFingerprint),
        forceRebuild);
  }

  SyncRemoteContext withForceRebuild(boolean nextForceRebuild) {
    return new SyncRemoteContext(
        platform,
        windowKind,
        foxMoveNumber,
        roomToken,
        liveTitleMove,
        recordCurrentMove,
        recordTotalMove,
        recordAtEnd,
        titleFingerprint,
        nextForceRebuild);
  }

  SyncRemoteContext withoutForceRebuild() {
    return withForceRebuild(false);
  }

  boolean supportsFoxRecovery() {
    return platform == SyncPlatform.FOX && recoveryMoveNumber().isPresent();
  }

  OptionalInt recoveryMoveNumber() {
    if (platform != SyncPlatform.FOX || !foxMoveNumber.isPresent()) {
      return OptionalInt.empty();
    }
    OptionalInt titleMoveNumber = titleMoveNumber();
    if (titleMoveNumber.isPresent() && titleMoveNumber.getAsInt() != foxMoveNumber.getAsInt()) {
      return OptionalInt.empty();
    }
    return foxMoveNumber;
  }

  boolean conflictsWith(SyncRemoteContext other) {
    if (other == null || platform != other.platform || windowKind != other.windowKind) {
      return true;
    }
    if (windowKind == WindowKind.LIVE_ROOM) {
      return !Objects.equals(roomToken, other.roomToken);
    }
    if (windowKind == WindowKind.RECORD_VIEW) {
      return !Objects.equals(titleFingerprint, other.titleFingerprint)
          || !sameOptionalInt(recordTotalMove, other.recordTotalMove);
    }
    return false;
  }

  private OptionalInt titleMoveNumber() {
    if (windowKind == WindowKind.LIVE_ROOM) {
      return liveTitleMove;
    }
    if (windowKind == WindowKind.RECORD_VIEW) {
      if (!recordCurrentMove.isPresent() && recordAtEnd && recordTotalMove.isPresent()) {
        return recordTotalMove;
      }
      return recordCurrentMove;
    }
    return OptionalInt.empty();
  }

  private static Optional<String> normalizeText(Optional<String> value) {
    if (!value.isPresent()) {
      return Optional.empty();
    }
    String trimmed = value.get().trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  private static boolean sameOptionalInt(OptionalInt left, OptionalInt right) {
    return left.isPresent() == right.isPresent()
        && (!left.isPresent() || left.getAsInt() == right.getAsInt());
  }
}
