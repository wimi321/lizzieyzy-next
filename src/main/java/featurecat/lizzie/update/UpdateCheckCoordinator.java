package featurecat.lizzie.update;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新检查单飞 and check-page lifecycle. Discovery stays synchronous; this type owns callbacks, not
 * Swing widgets or preference writes.
 */
final class UpdateCheckCoordinator {
  interface Page {
    void setCheckEnabled(boolean enabled);

    void setCloseAllowed(boolean allowed);

    void showStayOnPage(UpdateCheckResult result, UpdateCheckSelection snapshot);

    void disposeForOffer();
  }

  interface OfferHandoff {
    void openWindows(UpdateCheckSelection selection, WindowsUpdatePlan plan);

    void openPackage(UpdateCheckSelection selection, PackageUpdatePlan plan);
  }

  interface Discovery {
    UpdateCheckResult check(UpdateCheckSelection selection);
  }

  interface Runner {
    void runBackground(Runnable work);

    void runOnEdt(Runnable work);
  }

  private final AtomicBoolean inFlight = new AtomicBoolean(false);

  boolean start(
      Page page,
      UpdateCheckSelection snapshot,
      Discovery discovery,
      OfferHandoff handoff,
      Runner runner) {
    if (!inFlight.compareAndSet(false, true)) {
      return false;
    }
    page.setCheckEnabled(false);
    page.setCloseAllowed(false);
    runner.runBackground(
        () -> {
          UpdateCheckResult result = discovery.check(snapshot);
          runner.runOnEdt(() -> complete(page, snapshot, result, handoff));
        });
    return true;
  }

  private void complete(
      Page page,
      UpdateCheckSelection snapshot,
      UpdateCheckResult result,
      OfferHandoff handoff) {
    try {
      if (result != null
          && result.reason == UpdateCheckResult.Reason.OFFER
          && (result.windowsPlan != null || result.packagePlan != null)) {
        page.disposeForOffer();
        if (result.windowsPlan != null) {
          handoff.openWindows(snapshot, result.windowsPlan);
        } else {
          handoff.openPackage(snapshot, result.packagePlan);
        }
        return;
      }
      UpdateCheckResult stay =
          result != null && result.reason == UpdateCheckResult.Reason.OFFER
              ? UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER)
              : result;
      page.showStayOnPage(stay, snapshot);
      page.setCheckEnabled(true);
      page.setCloseAllowed(true);
    } finally {
      inFlight.set(false);
    }
  }
}
