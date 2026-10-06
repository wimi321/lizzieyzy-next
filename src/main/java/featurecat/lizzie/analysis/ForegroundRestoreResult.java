package featurecat.lizzie.analysis;

/** Result of returning foreground use; independent of request success or cancellation. */
public enum ForegroundRestoreResult {
  NOT_REQUIRED,
  SUCCEEDED,
  FAILED;

  public boolean permitsForegroundAnalysis() {
    return this != FAILED;
  }
}
