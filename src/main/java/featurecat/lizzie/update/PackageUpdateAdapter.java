package featurecat.lizzie.update;

final class PackageUpdateAdapter implements UpdateDiscovery.PlatformAdapter {
  private final boolean supported;
  private final boolean useDetectedTarget;
  private final String platform;
  private final String arch;
  private final String flavor;

  PackageUpdateAdapter() {
    this(isPackageRuntime(), true, null, null, null);
  }

  PackageUpdateAdapter(boolean supported, String platform, String arch, String flavor) {
    this(supported, false, platform, arch, flavor);
  }

  private PackageUpdateAdapter(
      boolean supported,
      boolean useDetectedTarget,
      String platform,
      String arch,
      String flavor) {
    this.supported = supported;
    this.useDetectedTarget = useDetectedTarget;
    this.platform = platform;
    this.arch = arch;
    this.flavor = flavor;
  }

  static boolean isPackageRuntime() {
    String current = PlatformUpdateService.currentPlatform();
    return "macos".equals(current) || "linux".equals(current);
  }

  @Override
  public boolean supports(UpdateCheckSelection selection) {
    return supported;
  }

  @Override
  public UpdateCheckResult plan(UpdateCheckSelection selection, UpdateManifest manifest) {
    String targetPlatform = platform;
    String targetArch = arch;
    String targetFlavor = flavor;
    if (useDetectedTarget) {
      targetPlatform = PlatformUpdateService.currentPlatform();
      targetArch = PlatformUpdateService.currentArch();
      targetFlavor = PlatformUpdateService.currentFlavor(targetPlatform);
    }
    UpdateManifest.PackageAsset selected =
        PlatformUpdateService.selectPackage(manifest, targetPlatform, targetArch, targetFlavor);
    if (selected == null) {
      return UpdateCheckResult.noPackage();
    }
    return UpdateCheckResult.offerPackage(
        new PackageUpdatePlan(
            manifest,
            selected,
            selection.installedVersion,
            targetPlatform,
            targetArch,
            targetFlavor));
  }
}
