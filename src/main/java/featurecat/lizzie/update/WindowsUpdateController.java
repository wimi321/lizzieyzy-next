package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.util.Optional;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private WindowsUpdateController() {}

  public static void checkForUpdate(Component parent) {
    if (UpdateVersion.shouldSkipAutomaticCheck(Lizzie.nextVersion)) {
      Utils.showMsg(
          UpdateText.tr(
              "WindowsUpdate.devBuild",
              "当前是开发版或未打包版本，无法检查更新。",
              "This development or unpackaged build cannot check for updates."));
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              try {
                if (WindowsUpdatePaths.isWindowsRuntime()) {
                  checkWindows(parent);
                } else {
                  checkPackage(parent);
                }
              } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(
                    () ->
                        Utils.showMsg(
                            UpdateText.tr(
                                    "WindowsUpdate.checkFailed", "检查更新失败", "Update check failed")
                                + ": "
                                + UpdateText.userFacingError(e)));
              }
            },
            "lizzie-update-manual");
    thread.setDaemon(true);
    thread.start();
  }

  private static void checkWindows(Component parent) throws Exception {
    WindowsUpdateService service = new WindowsUpdateService();
    Optional<WindowsUpdatePlan> maybePlan = service.checkForUpdate();
    if (maybePlan.isEmpty()) {
      showLatest();
      return;
    }
    WindowsUpdatePlan plan = maybePlan.get();
    SwingUtilities.invokeLater(
        () -> new WindowsUpdateDialog(parent, service, plan).setVisible(true));
  }

  private static void checkPackage(Component parent) throws Exception {
    PlatformUpdateService service = new PlatformUpdateService();
    Optional<PackageUpdatePlan> maybePlan = service.checkForUpdate();
    if (maybePlan.isEmpty()) {
      showLatest();
      return;
    }
    PackageUpdatePlan plan = maybePlan.get();
    SwingUtilities.invokeLater(
        () -> new PackageUpdateDialog(parent, service, plan).setVisible(true));
  }

  private static void showLatest() {
    SwingUtilities.invokeLater(
        () ->
            Utils.showMsg(
                UpdateText.tr(
                    "WindowsUpdate.latest",
                    "当前已经是最新正式版本。",
                    "You already have the latest stable version.")));
  }
}
