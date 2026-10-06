package featurecat.lizzie.update;

/** Terminal 更新检查结果 for one 更新检查选择快照. Contains no UI copy. */
public final class UpdateCheckResult {
  public enum Reason {
    UNAVAILABLE_BUILD,
    UNSUPPORTED_PLATFORM,
    NO_UPDATE,
    NO_PACKAGE,
    OFFER,
    FAILURE
  }

  public enum FailureKind {
    FETCH,
    INVALID_CANDIDATE,
    ADAPTER,
    UNEXPECTED
  }

  public final Reason reason;
  public final FailureKind failureKind;
  public final WindowsUpdatePlan windowsPlan;
  public final PackageUpdatePlan packagePlan;
  public final FailureKind stableCandidateFailure;
  public final FailureKind betaCandidateFailure;

  private UpdateCheckResult(
      Reason reason,
      FailureKind failureKind,
      WindowsUpdatePlan windowsPlan,
      PackageUpdatePlan packagePlan,
      FailureKind stableCandidateFailure,
      FailureKind betaCandidateFailure) {
    this.reason = reason;
    this.failureKind = failureKind;
    this.windowsPlan = windowsPlan;
    this.packagePlan = packagePlan;
    this.stableCandidateFailure = stableCandidateFailure;
    this.betaCandidateFailure = betaCandidateFailure;
  }

  public static UpdateCheckResult unavailableBuild() {
    return new UpdateCheckResult(Reason.UNAVAILABLE_BUILD, null, null, null, null, null);
  }

  public static UpdateCheckResult unsupportedPlatform() {
    return new UpdateCheckResult(Reason.UNSUPPORTED_PLATFORM, null, null, null, null, null);
  }

  public static UpdateCheckResult noUpdate() {
    return new UpdateCheckResult(Reason.NO_UPDATE, null, null, null, null, null);
  }

  public static UpdateCheckResult noPackage() {
    return new UpdateCheckResult(Reason.NO_PACKAGE, null, null, null, null, null);
  }

  public static UpdateCheckResult offerWindows(WindowsUpdatePlan plan) {
    return new UpdateCheckResult(Reason.OFFER, null, plan, null, null, null);
  }

  public static UpdateCheckResult offerPackage(PackageUpdatePlan plan) {
    return new UpdateCheckResult(Reason.OFFER, null, null, plan, null, null);
  }

  public static UpdateCheckResult failure(FailureKind kind) {
    return new UpdateCheckResult(
        Reason.FAILURE, kind == null ? FailureKind.UNEXPECTED : kind, null, null, null, null);
  }

  UpdateCheckResult withCandidateFailures(FailureKind stableFailure, FailureKind betaFailure) {
    if (stableFailure == null && betaFailure == null) {
      return this;
    }
    return new UpdateCheckResult(
        reason, failureKind, windowsPlan, packagePlan, stableFailure, betaFailure);
  }
}
