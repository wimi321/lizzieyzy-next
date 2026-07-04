package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.time.LocalDate;
import java.util.Optional;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private static final String LAST_CHECK_DATE_KEY = "windows-update-last-check-date";
  private static final String IGNORED_VERSION_KEY = "windows-update-ignored-version";
  private static final long AUTO_CHECK_DELAY_MS = 5000L;

  private WindowsUpdateController() {}

  public static void scheduleAutomaticCheck() {
    if (!WindowsUpdatePaths.isWindowsRuntime()
        || UpdateVersion.shouldSkipAutomaticCheck(Lizzie.nextVersion)
        || Lizzie.config == null
        || Lizzie.config.uiConfig == null) {
      return;
    }
    String today = LocalDate.now().toString();
    if (today.equals(Lizzie.config.uiConfig.optString(LAST_CHECK_DATE_KEY, ""))) {
      return;
    }
    Lizzie.config.uiConfig.put(LAST_CHECK_DATE_KEY, today);
    Thread thread =
        new Thread(
            () -> {
              try {
                Thread.sleep(AUTO_CHECK_DELAY_MS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              checkForUpdate(null, true);
            },
            "lizzie-windows-update-auto-check");
    thread.setDaemon(true);
    thread.start();
  }

  public static void checkForUpdate(Component parent, boolean automatic) {
    if (!WindowsUpdatePaths.isWindowsRuntime()) {
      if (!automatic) {
        Utils.showMsg("自动更新第一版仅支持 Windows。请在 GitHub Release 下载新版。");
      }
      return;
    }
    if (UpdateVersion.shouldSkipAutomaticCheck(Lizzie.nextVersion)) {
      if (!automatic) {
        Utils.showMsg("当前是开发版或未打包版本，不会自动检查更新。");
      }
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              WindowsUpdateService service = new WindowsUpdateService();
              try {
                Optional<WindowsUpdatePlan> maybePlan = service.checkForUpdate();
                if (maybePlan.isEmpty()) {
                  if (!automatic) {
                    SwingUtilities.invokeLater(() -> Utils.showMsg("当前已经是最新版本。"));
                  }
                  return;
                }
                WindowsUpdatePlan plan = maybePlan.get();
                String ignored = Lizzie.config.uiConfig.optString(IGNORED_VERSION_KEY, "");
                if (automatic && plan.manifest.releaseTag.equals(ignored)) {
                  return;
                }
                SwingUtilities.invokeLater(
                    () -> {
                      WindowsUpdateDialog dialog =
                          new WindowsUpdateDialog(parent, service, plan, IGNORED_VERSION_KEY);
                      dialog.setVisible(true);
                    });
              } catch (Exception e) {
                e.printStackTrace();
                if (!automatic) {
                  SwingUtilities.invokeLater(
                      () -> Utils.showMsg("检查更新失败: " + e.getLocalizedMessage()));
                }
              }
            },
            automatic ? "lizzie-windows-update-auto" : "lizzie-windows-update-manual");
    thread.setDaemon(true);
    thread.start();
  }
}
