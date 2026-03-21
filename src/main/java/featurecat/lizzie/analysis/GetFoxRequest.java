package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.FoxKifuDownload;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class GetFoxRequest {
  private static final String BASE_URL = "https://h5.foxwq.com/yehuDiamond/chessbook_local";

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
      String[] parts = command.split("\\s+");
      if (parts.length < 2) {
        return;
      }
      if ("user_name".equals(parts[0])) {
        handleUserName(parts[1]);
        return;
      }
      if ("uid".equals(parts[0])) {
        String uid = parts[1];
        String lastCode = parts.length >= 3 ? parts[2] : "0";
        emit(fetchChessList(uid, lastCode));
        return;
      }
      if ("chessid".equals(parts[0])) {
        emit(fetchSgf(parts[1]));
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
    // 仅支持 UID：不再尝试用户名反查。
    if (!text.matches("\\d+")) {
      JSONObject failed = new JSONObject();
      failed.put("result", 1);
      failed.put("resultstr", "Only Fox UID is supported. Please input numeric UID.");
      emit(failed.toString());
      return;
    }
    emit(fetchChessList(text, "0"));
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
    return httpGet(BASE_URL + "/YHWQFetchChess?chessid=" + url(chessid));
  }

  private String httpGet(String url) {
    java.net.HttpURLConnection conn = null;
    try {
      conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(15000);
      conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
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
      throw new RuntimeException(e);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private static String url(String text) {
    return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
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
}
