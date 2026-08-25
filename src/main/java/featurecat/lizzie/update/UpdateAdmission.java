package featurecat.lizzie.update;

import java.io.IOException;

public final class UpdateAdmission {
  public enum Kind {
    OFFER,
    NO_UPDATE,
    ERROR
  }

  public static final class FetchedManifest {
    public final UpdateManifest manifest;
    public final boolean signatureVerified;

    public FetchedManifest(UpdateManifest manifest, boolean signatureVerified) {
      this.manifest = manifest;
      this.signatureVerified = signatureVerified;
    }
  }

  public static final class Result {
    public final Kind kind;
    public final String message;
    public final UpdateManifest manifest;

    private Result(Kind kind, String message, UpdateManifest manifest) {
      this.kind = kind;
      this.message = message;
      this.manifest = manifest;
    }

    static Result offer(UpdateManifest manifest) {
      return new Result(Kind.OFFER, "", manifest);
    }

    static Result noUpdate(String message) {
      return new Result(Kind.NO_UPDATE, message, null);
    }

    static Result error(String message) {
      return new Result(Kind.ERROR, message, null);
    }
  }

  private UpdateAdmission() {}

  public static boolean shouldFetch(String installedVersion) {
    return !UpdateVersion.shouldSkipAutomaticCheck(installedVersion);
  }

  public static Result evaluate(
      UpdateChannel channel,
      String installedVersion,
      FetchedManifest fetched,
      Exception fetchFailure) {
    if (channel == null) {
      channel = UpdateChannel.STABLE;
    }
    if (!shouldFetch(installedVersion)) {
      return Result.error(
          UpdateText.tr(
              "WindowsUpdate.devBuild",
              "当前是开发版或未打包版本，无法检查更新。",
              "This development or unpackaged build cannot check for updates."));
    }
    if (fetchFailure != null || fetched == null || fetched.manifest == null) {
      return Result.error(fetchFailureMessage(channel));
    }
    UpdateManifest manifest = fetched.manifest;
    if (channel == UpdateChannel.BETA) {
      if (!fetched.signatureVerified
          || manifest.schemaVersion != UpdateManifest.SUPPORTED_SCHEMA_VERSION
          || !manifest.prerelease) {
        return Result.error(invalidTestPointerMessage());
      }
    } else if (manifest.prerelease) {
      return Result.noUpdate(noUpdateMessage(channel));
    }
    if (!UpdateVersion.isNewerThan(manifest.releaseTag, installedVersion)) {
      return Result.noUpdate(noUpdateMessage(channel));
    }
    return Result.offer(manifest);
  }

  public static Result evaluateClient(
      UpdateChannel channel, String installedVersion, UpdateManifestClient client) {
    FetchedManifest fetched = null;
    Exception failure = null;
    try {
      UpdateManifestClient.FetchResult fetch = client.fetchLatest();
      fetched = new FetchedManifest(fetch.manifest, fetch.signatureVerified);
    } catch (IOException e) {
      failure = e;
    }
    return evaluate(channel, installedVersion, fetched, failure);
  }

  public static String noUpdateMessage(UpdateChannel channel) {
    if (channel == UpdateChannel.BETA) {
      return UpdateText.tr(
          "WindowsUpdate.noUpdate.beta",
          "测试通道暂无更新版本。",
          "There is no newer version on the test channel.");
    }
    return UpdateText.tr(
        "WindowsUpdate.noUpdate.stable",
        "正式通道暂无更新版本。",
        "There is no newer version on the official channel.");
  }

  static String fetchFailureMessage(UpdateChannel channel) {
    if (channel == UpdateChannel.BETA) {
      return UpdateText.tr(
          "WindowsUpdate.fetchFailed.beta",
          "无法检查测试通道更新，请确认可以访问 GitHub。",
          "Could not check the test channel. GitHub must be reachable.");
    }
    return UpdateText.tr(
        "WindowsUpdate.fetchFailed.stable",
        "无法检查正式通道更新，请检查网络后重试。",
        "Could not check the official channel. Check your network and retry.");
  }

  private static String invalidTestPointerMessage() {
    return UpdateText.tr(
        "WindowsUpdate.invalidTestPointer",
        "测试通道指针不是已签名的测试版清单。",
        "The test channel pointer is not a signed test release.");
  }
}
