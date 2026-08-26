package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import javax.swing.SwingUtilities;

public final class WindowsUpdateController {
  private static final UpdateCheckCoordinator COORDINATOR = new UpdateCheckCoordinator();
  private static final UpdateCheckCoordinator.OfferHandoff HANDOFF = new ProductionHandoff();
  private static final UpdateCheckCoordinator.Runner RUNNER = new SwingRunner();

  private WindowsUpdateController() {}

  public static void openCheckUpdatePage(Component parent) {
    SwingUtilities.invokeLater(() -> new CheckUpdateDialog(parent).setVisible(true));
  }

  static void checkForUpdate(
      UpdateCheckCoordinator.Page page, UpdateCheckSelection snapshot) {
    COORDINATOR.start(page, snapshot, UpdateDiscovery::check, HANDOFF, RUNNER);
  }

  private static final class ProductionHandoff implements UpdateCheckCoordinator.OfferHandoff {
    @Override
    public void openWindows(UpdateCheckSelection selection, WindowsUpdatePlan plan) {
      WindowsUpdateService service = new WindowsUpdateService();
      new WindowsUpdateDialog(Lizzie.frame, service, plan).setVisible(true);
    }

    @Override
    public void openPackage(UpdateCheckSelection selection, PackageUpdatePlan plan) {
      PlatformUpdateService service = new PlatformUpdateService();
      new PackageUpdateDialog(Lizzie.frame, service, plan).setVisible(true);
    }
  }

  private static final class SwingRunner implements UpdateCheckCoordinator.Runner {
    @Override
    public void runBackground(Runnable work) {
      Thread thread = new Thread(work, "lizzie-update-manual");
      thread.setDaemon(true);
      thread.start();
    }

    @Override
    public void runOnEdt(Runnable work) {
      SwingUtilities.invokeLater(work);
    }
  }
}
