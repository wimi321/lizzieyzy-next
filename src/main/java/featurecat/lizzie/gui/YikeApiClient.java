package featurecat.lizzie.gui;

import featurecat.lizzie.util.NetworkProxy;
import featurecat.lizzie.util.Utils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

public class YikeApiClient {
  public static final String YIKE_LIVE_URL = "https://home.yikeweiqi.com/#/live";
  public static final String YIKE_GAME_URL = "https://home.yikeweiqi.com/#/game";

  private static final String APP_KEY = "3396jtzhK57XhJom";
  private static final String APP_SECRET = "hfdSXRKm0DQyLmNXmNCNkZpjy2o5q1Hk";
  private static final String LIST_URL = "https://api.yikeweiqi.com/v2/golive/list";
  private static final String DETAIL_URL_PREFIX = "https://api-new.yikeweiqi.com/v1/golives/";
  private static final int HTTP_TIMEOUT_MS = 10_000;
  private static final Random RANDOM = new Random();

  public YikeLivePage fetchLiveList(String official, int page, long since) throws IOException {
    Map<String, String> params = new LinkedHashMap<String, String>();
    params.put("p", Integer.toString(Math.max(page, 1)));
    params.put("since", Long.toString(Math.max(since, 0)));
    params.put("official", official == null ? "" : official);
    params.put("version", "2");
    return parseLiveList(fetch(buildUrl(LIST_URL, params)));
  }

  public YikeLiveDetail fetchLiveDetail(String id) throws IOException {
    return parseLiveDetail(fetch(detailUrl(id)));
  }

  public String fetch(String url) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
    connection.setConnectTimeout(HTTP_TIMEOUT_MS);
    connection.setReadTimeout(HTTP_TIMEOUT_MS);
    connection.setRequestMethod("GET");
    for (Map.Entry<String, String> header : createHeaders().entrySet()) {
      connection.setRequestProperty(header.getKey(), header.getValue());
    }
    int status = connection.getResponseCode();
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    String body = readAll(stream);
    if (status >= 400) {
      throw new IOException("Yike request failed: HTTP " + status + " " + body);
    }
    return body;
  }

  public static String detailUrl(String id) {
    return DETAIL_URL_PREFIX + encode(id);
  }

  static YikeLivePage parseLiveList(String response) throws IOException {
    JSONObject root = new JSONObject(response);
    if (root.optInt("Status") != 1200) {
      throw new IOException(root.optString("Message", "Yike live list request failed."));
    }
    JSONObject result = root.optJSONObject("Result");
    if (result == null) return new YikeLivePage(0, Collections.emptyList());
    JSONArray list = result.optJSONArray("list");
    List<YikeLiveGame> games = new ArrayList<YikeLiveGame>();
    if (list != null) {
      for (int i = 0; i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        if (item != null) games.add(YikeLiveGame.fromJson(item));
      }
    }
    return new YikeLivePage(result.optLong("since", 0), games);
  }

  static YikeLiveDetail parseLiveDetail(String response) throws IOException {
    JSONObject root = new JSONObject(response);
    if (root.optInt("status", -1) != 0) {
      throw new IOException(root.optString("message", "Yike live detail request failed."));
    }
    JSONObject result = root.optJSONObject("result");
    if (result == null) {
      throw new IOException("Yike live detail response does not contain result.");
    }
    return YikeLiveDetail.fromJson(result);
  }

  static Map<String, String> buildHeadersForTest(long currentTimeMillis, long nonce) {
    return buildHeaders(currentTimeMillis, nonce);
  }

  private static Map<String, String> createHeaders() {
    long currentTimeMillis = System.currentTimeMillis();
    long nonce = RANDOM.nextInt(100_000_000);
    return buildHeaders(currentTimeMillis, nonce);
  }

  private static Map<String, String> buildHeaders(long currentTimeMillis, long nonce) {
    Map<String, String> headers = new LinkedHashMap<String, String>();
    String currentTime = Long.toString(currentTimeMillis);
    String nonceText = Long.toString(nonce);
    String timestampHash = md5(currentTime);
    headers.put("AppKey", APP_KEY);
    headers.put("CurTime", currentTime);
    headers.put("CheckSum", sha1(APP_SECRET + nonceText + currentTime));
    headers.put("Nonce", nonceText);
    headers.put("usertoken", "-1");
    headers.put("version", "96813");
    headers.put("Platform", "web");
    headers.put("Content-Type", "application/json");
    headers.put("timestamp", currentTime);
    headers.put("uuid", "web");
    headers.put("accept-language", "zh-cn");
    headers.put("accesstoken", md5("@1%e$5*f@3" + timestampHash + "web"));
    return headers;
  }

  private static String buildUrl(String url, Map<String, String> params) {
    StringBuilder builder = new StringBuilder(url);
    builder.append("?");
    boolean first = true;
    for (Map.Entry<String, String> param : params.entrySet()) {
      if (!first) builder.append("&");
      builder.append(encode(param.getKey())).append("=").append(encode(param.getValue()));
      first = false;
    }
    return builder.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String readAll(InputStream stream) throws IOException {
    if (stream == null) return "";
    StringBuilder response = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        response.append(line);
      }
    }
    return response.toString();
  }

  private static String sha1(String value) {
    return digest("SHA-1", value);
  }

  private static String md5(String value) {
    return digest("MD5", value);
  }

  private static String digest(String algorithm, String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : bytes) {
        hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing digest algorithm: " + algorithm, e);
    }
  }

  public static class YikeLivePage {
    private final long since;
    private final List<YikeLiveGame> games;

    YikeLivePage(long since, List<YikeLiveGame> games) {
      this.since = since;
      this.games = Collections.unmodifiableList(new ArrayList<YikeLiveGame>(games));
    }

    public long getSince() {
      return since;
    }

    public List<YikeLiveGame> getGames() {
      return games;
    }
  }

  public static class YikeLiveGame {
    private static final DecimalFormat RATE_FORMAT = new DecimalFormat("0.#");

    private final int id;
    private final int version;
    private final long hall;
    private final long room;
    private final int status;
    private final String gameName;
    private final String blackName;
    private final String whiteName;
    private final String blackCounty;
    private final String whiteCounty;
    private final String gameDate;
    private final String broadcastTime;
    private final String finishOrder;
    private final String gameResult;
    private final String liveMember;
    private final int handsCount;
    private final long personTimes;
    private final boolean topFlag;
    private final boolean realtimeAnalysisFlag;
    private final double blackWinRate;
    private final double delta;

    private YikeLiveGame(JSONObject json) {
      id = json.optInt("Id");
      version = json.optInt("Version", 1);
      hall = json.optLong("hall", 0);
      room = json.optLong("room", 0);
      status = json.optInt("Status", 0);
      gameName = json.optString("GameName");
      blackName = json.optString("BlackName");
      whiteName = json.optString("WhiteName");
      blackCounty = json.optString("BlackCounty");
      whiteCounty = json.optString("WhiteCounty");
      gameDate = json.optString("GameDate");
      broadcastTime = json.optString("BroadcastTime");
      finishOrder = json.optString("FinishOrder");
      gameResult = json.optString("GameResult");
      liveMember = json.optString("LiveMember");
      handsCount = json.optInt("HandsCount");
      personTimes = json.optLong("PersonTimes");
      topFlag = json.optInt("TopFlag") == 1;
      realtimeAnalysisFlag = json.optInt("RealtimeAnalysisFlag") == 1;
      blackWinRate = json.optDouble("BlackWinRate", -1);
      delta = json.optDouble("Delta", 0);
    }

    static YikeLiveGame fromJson(JSONObject json) {
      return new YikeLiveGame(json);
    }

    public int getId() {
      return id;
    }

    public int getStatus() {
      return status;
    }

    public String getGameName() {
      return gameName;
    }

    public String getBlackName() {
      return blackName;
    }

    public String getWhiteName() {
      return whiteName;
    }

    public int getHandsCount() {
      return handsCount;
    }

    public String toRoomUrl() {
      String roomPath = version == 2 ? "new-room" : "room";
      return "https://home.yikeweiqi.com/#/live/" + roomPath + "/" + id + "/" + hall + "/" + room;
    }

    public String statusText() {
      if (status == 1) return "\u76F4\u64AD\u9884\u544A";
      if (status == 2) return "\u6B63\u5728\u76F4\u64AD";
      if (status == 3) return Utils.isBlank(gameResult) ? "\u5DF2\u7ED3\u675F" : gameResult;
      return "\u672A\u77E5";
    }

    public String timeText() {
      String date = Utils.isBlank(finishOrder) ? gameDate : finishOrder;
      if (Utils.isBlank(date)) return broadcastTime;
      if (Utils.isBlank(broadcastTime)) return date;
      return date + " " + broadcastTime;
    }

    public String playerText(boolean black) {
      String name = black ? blackName : whiteName;
      String county = black ? blackCounty : whiteCounty;
      return Utils.isBlank(county) ? name : name + " [" + county + "]";
    }

    public String liveMemberText() {
      return liveMember;
    }

    public String attentionText() {
      return Long.toString(personTimes);
    }

    public String winrateText() {
      if (!realtimeAnalysisFlag || blackWinRate < 0) return "";
      double whiteWinRate = Math.max(0, Math.min(100, 100 - blackWinRate));
      String rate =
          "\u9ED1 "
              + RATE_FORMAT.format(blackWinRate)
              + "% / \u767D "
              + RATE_FORMAT.format(whiteWinRate)
              + "%";
      if (Math.abs(delta) > 0.01) {
        rate += " / " + RATE_FORMAT.format(delta) + "\u76EE";
      }
      return rate;
    }

    public boolean isTopFlag() {
      return topFlag;
    }
  }

  public static class YikeLiveDetail {
    private final String sgf;
    private final int status;
    private final String result;

    private YikeLiveDetail(JSONObject json) {
      sgf = firstNonBlank(json.optString("sgf"), json.optString("clean_sgf"));
      status = json.optInt("status");
      result = json.optString("game_result");
    }

    static YikeLiveDetail fromJson(JSONObject json) {
      return new YikeLiveDetail(json);
    }

    public String getSgf() {
      return sgf;
    }

    public int getStatus() {
      return status;
    }

    public String getResult() {
      return result;
    }
  }

  static String firstNonBlank(String first, String second) {
    return Utils.isBlank(first) ? second : first;
  }
}
