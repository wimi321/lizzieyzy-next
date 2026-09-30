package featurecat.lizzie.teacher;

import java.util.List;

final class ChatGptText {
  private ChatGptText() {}

  static boolean isKnownError(String message) {
    if (message == null) return false;
    return List.of(
            "network",
            "permission",
            "ineligible",
            "loginRequired",
            "identity",
            "protocol",
            "timeout",
            "denied",
            "models",
            "reasoning",
            "incomplete",
            "busy",
            "storage",
            "credentialsUnavailable",
            "modelRemoved",
            "limit",
            "browser")
        .stream()
        .anyMatch(code -> ChatGptHttp.error(code).getMessage().equals(message));
  }
}
