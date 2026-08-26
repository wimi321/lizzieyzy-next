package featurecat.lizzie.update;

import java.io.IOException;

final class WindowsUpdateAdapter implements UpdateDiscovery.PlatformAdapter {
  private final boolean supported;
  private final boolean useDetectedTarget;
  private final String flavor;
  private final InstalledUpdateState installed;

  WindowsUpdateAdapter() {
    this(WindowsUpdatePaths.isWindowsRuntime(), true, null, null);
  }

  WindowsUpdateAdapter(boolean supported, String flavor, InstalledUpdateState installed) {
    this(supported, false, flavor, installed);
  }

  private WindowsUpdateAdapter(
      boolean supported,
      boolean useDetectedTarget,
      String flavor,
      InstalledUpdateState installed) {
    this.supported = supported;
    this.useDetectedTarget = useDetectedTarget;
    this.flavor = flavor;
    this.installed = installed;
  }

  @Override
  public boolean supports(UpdateCheckSelection selection) {
    return supported;
  }

  @Override
  public UpdateCheckResult plan(UpdateCheckSelection selection, UpdateManifest manifest) {
    try {
      String targetFlavor = flavor;
      InstalledUpdateState state = installed;
      if (useDetectedTarget) {
        WindowsUpdatePaths paths = WindowsUpdatePaths.detect();
        targetFlavor = paths.flavor;
        state =
            InstalledUpdateState.read(paths.appDir, selection.installedVersion, paths.flavor);
      }
      if (!hasMatchingWindowsCore(manifest, targetFlavor)) {
        return UpdateCheckResult.noPackage();
      }
      WindowsUpdatePlan plan =
          WindowsUpdatePlan.create(manifest, state, selection.installedVersion, targetFlavor);
      if (!plan.hasUpdate()) {
        return UpdateCheckResult.noUpdate();
      }
      return UpdateCheckResult.offerWindows(plan);
    } catch (IOException e) {
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER);
    }
  }

  private static boolean hasMatchingWindowsCore(UpdateManifest manifest, String flavor) {
    if (manifest == null) {
      return false;
    }
    for (UpdateManifest.Component component : manifest.components) {
      if ("core".equals(component.id) && component.matches("windows", flavor)) {
        return true;
      }
    }
    return false;
  }
}
