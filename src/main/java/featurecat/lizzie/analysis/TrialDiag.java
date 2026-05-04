package featurecat.lizzie.analysis;

/**
 * 试下排查诊断日志开关。集中一处避免 4 个文件各自调 System.getProperty。 启动加 {@code -Dlizzie.trial.diag=true} 打开； 字段是
 * {@code static final}，JIT 会把诊断分支当 dead code 消除，关闭时零开销。
 */
public final class TrialDiag {
  public static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("lizzie.trial.diag", "false"));

  private TrialDiag() {}
}
