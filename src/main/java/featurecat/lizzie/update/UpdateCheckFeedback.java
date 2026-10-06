package featurecat.lizzie.update;

/** Presentation mapping from 更新检查结果 to localization keys. Contains no discovery policy. */
final class UpdateCheckFeedback {
  private UpdateCheckFeedback() {}

  static String warning(UpdateCheckResult result) {
    if (result == null
        || (result.stableCandidateFailure == null) == (result.betaCandidateFailure == null)) {
      return "";
    }
    return result.stableCandidateFailure != null
        ? UpdateText.tr(
            "WindowsUpdate.partialCheck.stable",
            "检查不完整：无法验证 GitHub 正式版清单，仅使用已验证的测试版结果。",
            "Check incomplete: the GitHub official release could not be verified. Only the verified test release was considered.")
        : UpdateText.tr(
            "WindowsUpdate.partialCheck.beta",
            "检查不完整：无法验证 GitHub 测试版清单，仅使用已验证的正式版结果。",
            "Check incomplete: the GitHub test release could not be verified. Only the verified official release was considered.");
  }

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
            "已验证的正式版或测试版候选没有比当前安装更新的版本。",
            "No verified official or test release is newer than the installed version.");
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
            "无法验证 GitHub 正式版和测试版清单，请检查网络后重试。",
            "Neither the GitHub official release nor the test release could be verified. Check your network and retry.");
      case "WindowsUpdate.fetchFailed.stable":
        return UpdateText.tr(
            "WindowsUpdate.fetchFailed.stable",
            "无法检查正式通道更新，请检查网络后重试。",
            "Could not check the official channel. Check your network and retry.");
      default:
        return UpdateText.tr("WindowsUpdate.checkFailed", "检查更新失败", "Update check failed");
    }
  }
}
