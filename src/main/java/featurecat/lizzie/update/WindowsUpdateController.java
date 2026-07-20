package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.util.Optional;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private WindowsUpdateController() {}

  public static void checkForUpdate(Component parent) {
    if (!WindowsUpdatePaths.isWindowsRuntime()) {
      Utils.showMsg("自动更新第一版仅支持 Windows。请在 GitHub Release 下载新版。");
      return;
    }
    if (UpdateVersion.shouldSkipAutomaticCheck(Lizzie.nextVersion)) {
      Utils.showMsg("当前是开发版或未打包版本，无法检查更新。");
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              WindowsUpdateService service = new WindowsUpdateService();
              try {
                Optional<WindowsUpdatePlan> maybePlan = service.checkForUpdate();
                if (maybePlan.isEmpty()) {
                  SwingUtilities.invokeLater(() -> Utils.showMsg("当前已经是最新正式版本。"));
                  return;
                }
                WindowsUpdatePlan plan = maybePlan.get();
                SwingUtilities.invokeLater(
                    () -> {
                      WindowsUpdateDialog dialog =
                          new WindowsUpdateDialog(parent, service, plan);
                      dialog.setVisible(true);
                    });
              } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(
                    () -> Utils.showMsg("检查更新失败: " + e.getLocalizedMessage()));
              }
            },
            "lizzie-windows-update-manual");
    thread.setDaemon(true);
    thread.start();
  }
}
