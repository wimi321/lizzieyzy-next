package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.Set;
import org.json.JSONObject;

/** Safe diagnostic metadata only: never retains response text, tokens or provider URLs. */
final class ChatGptApiException extends IOException {
  final int status;
  final String code;
  final String parameter;
  final String requestId;

  ChatGptApiException(int status, JSONObject payload, String requestId) {
    super(ChatGptHttp.error(messageCode(status, code(payload))).getMessage());
    this.status = status;
    code = code(payload);
    JSONObject error = payload.optJSONObject("error");
    parameter = safe(error == null ? "" : error.optString("param"));
    this.requestId = safe(requestId);
  }

  boolean refreshRevoked() {
    return Set.of(
            "invalid_grant",
            "invalid_refresh_token",
            "token_expired",
            "refresh_token_expired",
            "refresh_token_invalidated",
            "refresh_token_reused")
        .contains(code);
  }

  private static String code(JSONObject payload) {
    JSONObject error = payload.optJSONObject("error");
    return safe(error == null ? payload.optString("error", "") : error.optString("code"));
  }

  private static String safe(String value) {
    return value != null && value.matches("[A-Za-z0-9_.:-]{1,160}") ? value : "";
  }

  private static String messageCode(int status, String code) {
    return switch (code) {
      case "subscription_sharing_usage_limit_exceeded" -> "limit";
      case "subscription_sharing_user_not_eligible" -> "ineligible";
      case "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
          "permission";
      case "subscription_sharing_unsupported_capability",
          "subscription_sharing_route_not_supported",
          "invalid_client" ->
          "protocol";
      case "invalid_grant",
          "invalid_refresh_token",
          "token_expired",
          "refresh_token_expired",
          "refresh_token_invalidated",
          "refresh_token_reused",
          "subscription_sharing_invalid_user" ->
          "loginRequired";
      case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
          "network";
      default ->
          status == 429
              ? "limit"
              : status == 401
                  ? "loginRequired"
                  : status == 403 ? "permission" : status == 400 ? "protocol" : "network";
    };
  }
}
