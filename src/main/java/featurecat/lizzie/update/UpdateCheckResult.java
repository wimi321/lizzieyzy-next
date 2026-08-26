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
    INVALID_TEST_POINTER,
    ADAPTER,
    UNEXPECTED
  }

  public final Reason reason;
  public final FailureKind failureKind;
  public final WindowsUpdatePlan windowsPlan;
  public final PackageUpdatePlan packagePlan;

  private UpdateCheckResult(
      Reason reason,
      FailureKind failureKind,
      WindowsUpdatePlan windowsPlan,
      PackageUpdatePlan packagePlan) {
    this.reason = reason;
    this.failureKind = failureKind;
    this.windowsPlan = windowsPlan;
    this.packagePlan = packagePlan;
  }

  public static UpdateCheckResult unavailableBuild() {
    return new UpdateCheckResult(Reason.UNAVAILABLE_BUILD, null, null, null);
  }

  public static UpdateCheckResult unsupportedPlatform() {
    return new UpdateCheckResult(Reason.UNSUPPORTED_PLATFORM, null, null, null);
  }

  public static UpdateCheckResult noUpdate() {
    return new UpdateCheckResult(Reason.NO_UPDATE, null, null, null);
  }

  public static UpdateCheckResult noPackage() {
    return new UpdateCheckResult(Reason.NO_PACKAGE, null, null, null);
  }

  public static UpdateCheckResult offerWindows(WindowsUpdatePlan plan) {
    return new UpdateCheckResult(Reason.OFFER, null, plan, null);
  }

  public static UpdateCheckResult offerPackage(PackageUpdatePlan plan) {
    return new UpdateCheckResult(Reason.OFFER, null, null, plan);
  }

  public static UpdateCheckResult failure(FailureKind kind) {
    return new UpdateCheckResult(
        Reason.FAILURE, kind == null ? FailureKind.UNEXPECTED : kind, null, null);
  }
}
