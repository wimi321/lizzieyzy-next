package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.FoxKifuDownload;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class GetFoxRequest {
  private static final String BASE_URL = "https://h5.foxwq.com/yehuDiamond/chessbook_local";
  private static final String QUERY_USER_URL = "https://newframe.foxwq.com/cgi/QueryUserInfoPanel";
  private static final int HTTP_CONNECT_TIMEOUT_MS = 20000;
  private static final int HTTP_READ_TIMEOUT_MS = 25000;
  private static final int HTTP_MAX_RETRIES = 3;

  private ExecutorService executor;
  private final FoxKifuDownload foxKifuDownload;

  public GetFoxRequest(FoxKifuDownload foxKifuDownload) {
    this.foxKifuDownload = foxKifuDownload;
    try {
      executor = Executors.newSingleThreadScheduledExecutor();
    } catch (Exception e) {
      Utils.showMsg(e.getLocalizedMessage());
    }
  }

  public void sendCommand(String command) {
    if (command == null || command.trim().isEmpty() || executor == null || executor.isShutdown()) {
      return;
    }
    executor.execute(() -> handleCommand(command.trim()));
  }

  public void shutdown() {
    if (executor != null && !executor.isShutdown()) {
      executor.shutdownNow();
    }
  }

  private void handleCommand(String command) {
    try {
      String[] parts = command.split("\\s+", 2);
      if (parts.length < 2) {
        return;
      }
      String action = parts[0];
      String arguments = parts[1].trim();
      if (arguments.isEmpty()) {
        return;
      }
      if ("user_name".equals(action)) {
        handleUserName(arguments);
        return;
      }
      if ("uid".equals(action)) {
        String[] uidArgs = arguments.split("\\s+", 2);
        String uid = uidArgs[0];
        String lastCode = uidArgs.length >= 2 ? uidArgs[1].trim() : "0";
        emit(fetchChessList(uid, lastCode));
        return;
      }
      if ("chessid".equals(action)) {
        emit(fetchSgf(arguments));
      }
    } catch (Exception e) {
      emitError(e.getMessage());
    }
  }

  private void handleUserName(String userInput) {
    String text = userInput == null ? "" : userInput.trim();
    if (text.isEmpty()) {
      emitError("empty fox user");
      return;
    }
    if (text.matches("\\d+")) {
      emit(wrapChessListWithUserInfo(fetchChessList(text, "0"), text, text, text));
      return;
    }

    JSONObject userInfo = queryUserByName(text);
    String uid = userInfo.opt("uid").toString().trim();
    String nickname =
        firstNonEmpty(
            userInfo.optString("username", ""),
            userInfo.optString("name", ""),
            userInfo.optString("englishname", ""),
            text);
    emit(wrapChessListWithUserInfo(fetchChessList(uid, "0"), uid, nickname, text));
  }

  private String fetchChessList(String uid, String lastCode) {
    return httpGet(
        BASE_URL
            + "/YHWQFetchChessList?srcuid=0&dstuid="
            + url(uid)
            + "&type=1&lastcode="
            + url(lastCode)
            + "&searchkey=&uin="
            + url(uid));
  }

  private String fetchSgf(String chessid) {
    return normalizeFoxSgfPayload(httpGet(BASE_URL + "/YHWQFetchChess?chessid=" + url(chessid)));
  }

  private JSONObject queryUserByName(String nickname) {
    JSONObject json =
        new JSONObject(httpGet(QUERY_USER_URL + "?srcuid=0&username=" + url(nickname)));
    int result = json.has("result") ? json.optInt("result", -1) : json.optInt("errcode", -1);
    if (result != 0) {
      throw new RuntimeException(
          firstNonEmpty(
              json.optString("resultstr", ""),
              json.optString("errmsg", ""),
              "Can't find a Fox account for nickname: " + nickname));
    }
    String uid = json.opt("uid") == null ? "" : json.opt("uid").toString().trim();
    if (uid.isEmpty()) {
      throw new RuntimeException("Fox account was found, but the numeric UID was empty.");
    }
    return json;
  }

  private String wrapChessListWithUserInfo(
      String payload, String uid, String nickname, String queryText) {
    JSONObject json = new JSONObject(payload);
    if (!uid.trim().isEmpty()) {
      json.put("fox_uid", uid.trim());
    }
    String safeNickname = nickname == null ? "" : nickname.trim();
    if (!safeNickname.isEmpty()) {
      json.put("fox_nickname", safeNickname);
    }
    String safeQuery = queryText == null ? "" : queryText.trim();
    if (!safeQuery.isEmpty()) {
      json.put("fox_query", safeQuery);
    }
    return json.toString();
  }

  private String httpGet(String url) {
    IOException lastError = null;
    for (int attempt = 1; attempt <= HTTP_MAX_RETRIES; attempt++) {
      java.net.HttpURLConnection conn = null;
      try {
        conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        int code = conn.getResponseCode();
        java.io.InputStream in =
            code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
          throw new IOException("HTTP " + code + " with empty body");
        }
        try (java.io.InputStream input = in;
            java.util.Scanner scanner = new java.util.Scanner(input, "UTF-8").useDelimiter("\\A")) {
          return scanner.hasNext() ? scanner.next() : "";
        }
      } catch (IOException e) {
        lastError = e;
        if (attempt >= HTTP_MAX_RETRIES) {
          break;
        }
        try {
          Thread.sleep(350L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    }
    throw new RuntimeException(lastError);
  }

  private static String url(String text) {
    return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
  }

  private static String firstNonEmpty(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return "";
  }

  private void emit(String payload) {
    if (payload != null && !payload.trim().isEmpty()) {
      foxKifuDownload.receiveResult(payload);
    }
  }

  private void emitError(String msg) {
    JSONObject error = new JSONObject();
    error.put("result", 1);
    error.put("resultstr", msg == null ? "request failed" : msg);
    emit(error.toString());
  }

  private String normalizeFoxSgfPayload(String payload) {
    if (payload == null || payload.trim().isEmpty()) {
      return payload;
    }
    try {
      JSONObject json = new JSONObject(payload);
      String sgf = json.optString("chess", "");
      if (!sgf.trim().isEmpty()) {
        json.put("chess", retainMainLineOnly(sanitizeFoxSgf(sgf)));
      }
      return json.toString();
    } catch (Exception e) {
      return payload;
    }
  }

  private String sanitizeFoxSgf(String sgf) {
    if (sgf == null || sgf.trim().isEmpty()) {
      return sgf;
    }
    String text = sgf.replace("\uFEFF", "").trim();
    StringBuilder out = new StringBuilder(text.length());
    boolean insideValue = false;
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (insideValue) {
        out.append(current);
        if (current == '\\' && i + 1 < text.length()) {
          out.append(text.charAt(++i));
        } else if (current == ']') {
          insideValue = false;
        }
        continue;
      }
      if (current == '\\') {
        continue;
      }
      out.append(current);
      if (current == '[') {
        insideValue = true;
      }
    }
    return out.toString();
  }

  private String retainMainLineOnly(String sgf) {
    if (sgf == null || sgf.trim().isEmpty()) {
      return sgf;
    }
    try {
      return new SgfMainLineReducer(sgf).reduce();
    } catch (RuntimeException e) {
      return sgf;
    }
  }

  private static final class SgfMainLineReducer {
    private final String input;
    private int index = 0;

    private SgfMainLineReducer(String input) {
      this.input = input;
    }

    private String reduce() {
      StringBuilder out = new StringBuilder(input.length());
      skipWhitespace();
      boolean hasTree = false;
      while (index < input.length()) {
        char current = input.charAt(index);
        if (current == '(') {
          appendTree(out);
          hasTree = true;
        } else if (Character.isWhitespace(current)) {
          index++;
        } else {
          throw new IllegalStateException("Unexpected SGF token: " + current);
        }
        skipWhitespace();
      }
      return hasTree ? out.toString() : input;
    }

    private void appendTree(StringBuilder out) {
      expect('(');
      out.append('(');
      skipWhitespace();
      appendTreeBody(out);
      expect(')');
      out.append(')');
    }

    private void appendTreeBody(StringBuilder out) {
      while (index < input.length() && input.charAt(index) == ';') {
        appendNode(out);
        skipWhitespace();
      }
      boolean keptChild = false;
      while (index < input.length() && input.charAt(index) == '(') {
        if (!keptChild) {
          appendChildMainLine(out);
          keptChild = true;
        } else {
          skipTree();
        }
        skipWhitespace();
      }
    }

    private void appendChildMainLine(StringBuilder out) {
      expect('(');
      skipWhitespace();
      appendTreeBody(out);
      expect(')');
    }

    private void appendNode(StringBuilder out) {
      expect(';');
      out.append(';');
      while (true) {
        skipWhitespace();
        if (index >= input.length()) {
          return;
        }
        char current = input.charAt(index);
        if (current == ';' || current == '(' || current == ')') {
          return;
        }
        appendProperty(out);
      }
    }

    private void appendProperty(StringBuilder out) {
      int nameStart = index;
      while (index < input.length()) {
        char current = input.charAt(index);
        if ((current >= 'A' && current <= 'Z') || (current >= 'a' && current <= 'z')) {
          index++;
        } else {
          break;
        }
      }
      if (nameStart == index) {
        throw new IllegalStateException("Invalid SGF property");
      }
      out.append(input, nameStart, index);
      skipWhitespace();
      boolean hasValue = false;
      while (index < input.length() && input.charAt(index) == '[') {
        appendValue(out);
        skipWhitespace();
        hasValue = true;
      }
      if (!hasValue) {
        throw new IllegalStateException("Missing SGF property value");
      }
    }

    private void appendValue(StringBuilder out) {
      expect('[');
      out.append('[');
      while (index < input.length()) {
        char current = input.charAt(index++);
        out.append(current);
        if (current == '\\') {
          if (index < input.length()) {
            out.append(input.charAt(index++));
          }
        } else if (current == ']') {
          return;
        }
      }
      throw new IllegalStateException("Unterminated SGF value");
    }

    private void skipTree() {
      int depth = 0;
      while (index < input.length()) {
        char current = input.charAt(index++);
        if (current == '(') {
          depth++;
        } else if (current == ')') {
          depth--;
          if (depth == 0) {
            return;
          }
        } else if (current == '[') {
          skipValue();
        }
      }
      throw new IllegalStateException("Unterminated SGF variation");
    }

    private void skipValue() {
      while (index < input.length()) {
        char current = input.charAt(index++);
        if (current == '\\') {
          if (index < input.length()) {
            index++;
          }
        } else if (current == ']') {
          return;
        }
      }
      throw new IllegalStateException("Unterminated SGF value");
    }

    private void expect(char expected) {
      if (index >= input.length() || input.charAt(index) != expected) {
        throw new IllegalStateException("Expected '" + expected + "'");
      }
      index++;
    }

    private void skipWhitespace() {
      while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
        index++;
      }
    }
  }
}
