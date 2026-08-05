package featurecat.lizzie.update;

public final class PackageUpdatePlan {
  public final UpdateManifest manifest;
  public final UpdateManifest.PackageAsset packageAsset;
  public final String currentVersion;
  public final String platform;
  public final String arch;
  public final String flavor;

  PackageUpdatePlan(
      UpdateManifest manifest,
      UpdateManifest.PackageAsset packageAsset,
      String currentVersion,
      String platform,
      String arch,
      String flavor) {
    this.manifest = manifest;
    this.packageAsset = packageAsset;
    this.currentVersion = currentVersion;
    this.platform = platform;
    this.arch = arch;
    this.flavor = flavor;
  }
}
