package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.TencentKifuDownload;
import featurecat.lizzie.util.NetworkProxy;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class GetTencentRequest {
  private static final String CHESS_LIST_URL =
      "https://cgi.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChessList";
  private static final String CHESS_DETAIL_URL =
      "https://happyapp.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChess";
  private static final String DEFAULT_SESSION = "lizzieyzy-next";
  private static final int DEFAULT_FETCH_NUM = 100;
  private static final int HTTP_CONNECT_TIMEOUT_MS = 20000;
  private static final int HTTP_READ_TIMEOUT_MS = 25000;
  private static final int HTTP_MAX_RETRIES = 3;
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

  private ExecutorService executor;
  private final TencentKifuDownload tencentKifuDownload;

  public GetTencentRequest(TencentKifuDownload tencentKifuDownload) {
    this.tencentKifuDownload = tencentKifuDownload;
    try {
      executor = Executors.newSingleThreadExecutor();
    } catch (Exception e) {
      Utils.showMsg(e.getLocalizedMessage());
    }
  }

  public void search(String username) {
    fetchList(username, "0");
  }

  public void fetchNextPage(String username, String lastCode) {
    fetchList(username, lastCode);
  }

  public void fetchKifu(String chessId) {
    String safeChessId = chessId == null ? "" : chessId.trim();
    if (safeChessId.isEmpty() || executor == null || executor.isShutdown()) {
      return;
    }
    executor.execute(
        () -> {
          try {
            emit(fetchSgf(safeChessId));
          } catch (Exception e) {
            emitError(e.getMessage());
          }
        });
  }

  public void shutdown() {
    if (executor != null && !executor.isShutdown()) {
      executor.shutdownNow();
    }
  }

  private void fetchList(String username, String lastCode) {
    String safeUsername = username == null ? "" : username.trim();
    if (safeUsername.isEmpty() || executor == null || executor.isShutdown()) {
      return;
    }
    String safeLastCode = lastCode == null || lastCode.trim().isEmpty() ? "0" : lastCode.trim();
    executor.execute(
        () -> {
          try {
            String payload =
                httpGet(
                    buildChessListUrl(
                        safeUsername,
                        resolveSrcUid(safeUsername),
                        safeLastCode,
                        DEFAULT_FETCH_NUM,
                        DEFAULT_SESSION));
            emit(wrapListWithQuery(payload, safeUsername));
          } catch (Exception e) {
            emitError(e.getMessage());
          }
        });
  }

  private String fetchSgf(String chessId) {
    return normalizeTencentSgfPayload(httpGet(buildChessDetailUrl(chessId)));
  }

  static String buildChessListUrl(
      String username, String srcUid, String lastCode, int fetchNum, String session) {
    LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
    params.put("type", "7");
    params.put("lastCode", lastCode == null || lastCode.trim().isEmpty() ? "0" : lastCode.trim());
    params.put("username", username == null ? "" : username.trim());
    params.put("srcuid", srcUid == null ? "" : srcUid.trim());
    params.put(
        "txwqsession",
        session == null || session.trim().isEmpty() ? DEFAULT_SESSION : session.trim());
    params.put("fetchnum", String.valueOf(Math.max(1, fetchNum)));
    return appendQuery(CHESS_LIST_URL, params);
  }

  static String buildChessDetailUrl(String chessId) {
    LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
    params.put("chessid", chessId == null ? "" : chessId.trim());
    return appendQuery(CHESS_DETAIL_URL, params);
  }

  static String resolveSrcUid(String username) {
    String safeUsername = username == null ? "" : username.trim();
    return safeUsername.matches("\\d+") ? safeUsername : "";
  }

  private static String appendQuery(String baseUrl, LinkedHashMap<String, String> params) {
    StringBuilder builder = new StringBuilder(baseUrl);
    builder.append('?');
    boolean first = true;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (!first) {
        builder.append('&');
      }
      first = false;
      builder.append(url(entry.getKey())).append('=').append(url(entry.getValue()));
    }
    return builder.toString();
  }

  private String wrapListWithQuery(String payload, String queryText) {
    JSONObject json = new JSONObject(payload);
    String safeQuery = queryText == null ? "" : queryText.trim();
    if (!safeQuery.isEmpty()) {
      json.put("tencent_query", safeQuery);
    }
    return json.toString();
  }

  private String httpGet(String url) {
    IOException lastError = null;
    for (int attempt = 1; attempt <= HTTP_MAX_RETRIES; attempt++) {
      java.net.HttpURLConnection conn = null;
      try {
        conn = (java.net.HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
          throw new IOException("HTTP " + code + " with empty body");
        }
        try (InputStream input = in;
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

  private void emit(String payload) {
    if (payload != null && !payload.trim().isEmpty()) {
      tencentKifuDownload.receiveResult(payload);
    }
  }

  private void emitError(String msg) {
    JSONObject error = new JSONObject();
    error.put("result", 1);
    error.put("resultstr", msg == null ? "request failed" : msg);
    emit(error.toString());
  }

  private String normalizeTencentSgfPayload(String payload) {
    if (payload == null || payload.trim().isEmpty()) {
      return payload;
    }
    try {
      JSONObject json = new JSONObject(payload);
      String sgf = json.optString("chess", "");
      if (!sgf.trim().isEmpty()) {
        json.put("chess", sanitizeTencentSgf(sgf));
      }
      return json.toString();
    } catch (Exception e) {
      return payload;
    }
  }

  private String sanitizeTencentSgf(String sgf) {
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

  private static String url(String text) {
    return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
  }
}
