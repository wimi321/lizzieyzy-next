package featurecat.lizzie.update;

/** Presentation mapping from 更新检查结果 to localization keys. Contains no discovery policy. */
final class UpdateCheckFeedback {
  private UpdateCheckFeedback() {}

  static String key(UpdateCheckResult result, UpdateChannel channel) {
    if (result == null) {
      return "WindowsUpdate.checkFailed";
    }
    switch (result.reason) {
      case UNAVAILABLE_BUILD:
        return "WindowsUpdate.devBuild";
      case UNSUPPORTED_PLATFORM:
        return "WindowsUpdate.unsupportedPlatform";
      case NO_UPDATE:
        return channel == UpdateChannel.BETA
            ? "WindowsUpdate.noUpdate.beta"
            : "WindowsUpdate.noUpdate.stable";
      case NO_PACKAGE:
        return "WindowsUpdate.noPackage";
      case FAILURE:
        if (result.failureKind == UpdateCheckResult.FailureKind.FETCH) {
          return channel == UpdateChannel.BETA
              ? "WindowsUpdate.fetchFailed.beta"
              : "WindowsUpdate.fetchFailed.stable";
        }
        if (result.failureKind == UpdateCheckResult.FailureKind.INVALID_TEST_POINTER) {
          return "WindowsUpdate.invalidTestPointer";
        }
        return "WindowsUpdate.checkFailed";
      case OFFER:
      default:
        return "WindowsUpdate.checkFailed";
    }
  }

  static String message(UpdateCheckResult result, UpdateChannel channel) {
    switch (key(result, channel)) {
      case "WindowsUpdate.devBuild":
        return UpdateText.tr(
            "WindowsUpdate.devBuild",
            "当前是开发版或未打包版本，无法检查更新。",
            "This development or unpackaged build cannot check for updates.");
      case "WindowsUpdate.unsupportedPlatform":
        return UpdateText.tr(
            "WindowsUpdate.unsupportedPlatform",
            "当前平台不支持应用内更新。",
            "This platform cannot check for in-app updates.");
      case "WindowsUpdate.noUpdate.beta":
        return UpdateText.tr(
            "WindowsUpdate.noUpdate.beta",
            "测试通道暂无更新版本。",
            "There is no newer version on the test channel.");
      case "WindowsUpdate.noUpdate.stable":
        return UpdateText.tr(
            "WindowsUpdate.noUpdate.stable",
            "正式通道暂无更新版本。",
            "There is no newer version on the official channel.");
      case "WindowsUpdate.noPackage":
        return UpdateText.tr(
            "WindowsUpdate.noPackage",
            "已有更新版本，但没有匹配当前安装的更新包。",
            "A newer release exists, but no matching installable update is available.");
      case "WindowsUpdate.fetchFailed.beta":
        return UpdateText.tr(
            "WindowsUpdate.fetchFailed.beta",
            "无法检查测试通道更新，请确认可以访问 GitHub。",
            "Could not check the test channel. GitHub must be reachable.");
      case "WindowsUpdate.fetchFailed.stable":
        return UpdateText.tr(
            "WindowsUpdate.fetchFailed.stable",
            "无法检查正式通道更新，请检查网络后重试。",
            "Could not check the official channel. Check your network and retry.");
      case "WindowsUpdate.invalidTestPointer":
        return UpdateText.tr(
            "WindowsUpdate.invalidTestPointer",
            "测试通道指针不是已签名的测试版清单。",
            "The test channel pointer is not a signed test release.");
      default:
        return UpdateText.tr(
            "WindowsUpdate.checkFailed", "检查更新失败", "Update check failed");
    }
  }
}
