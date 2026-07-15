package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.util.NetworkProxy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapts KaTrain-compatible KataGo Analysis Engine WebSocket links to Lizzie's GTP flow. */
public class KataGoAnalysisWebSocketTransport implements EngineTransport {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final int DEFAULT_BOARD_SIZE = 19;
  private static final int LIVE_ANALYSIS_MAX_VISITS = 10_000_000;
  private static final int GENMOVE_MAX_VISITS = 256;
  private static final String CHINESE_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"SIMPLE\","
          + "\"scoring\":\"AREA\",\"suicide\":false,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"N\"}";
  private static final String CHINESE_POSITIONAL_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"POSITIONAL\","
          + "\"scoring\":\"AREA\",\"suicide\":false,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"N\"}";
  private static final String JAPANESE_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"SIMPLE\","
          + "\"scoring\":\"TERRITORY\",\"suicide\":false,\"tax\":\"SEKI\","
          + "\"whiteHandicapBonus\":\"0\"}";
  private static final String STONE_SCORING_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"SIMPLE\","
          + "\"scoring\":\"AREA\",\"suicide\":false,\"tax\":\"ALL\","
          + "\"whiteHandicapBonus\":\"0\"}";
  private static final String AGA_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"SITUATIONAL\","
          + "\"scoring\":\"AREA\",\"suicide\":false,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"N-1\"}";
  private static final String NEW_ZEALAND_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":false,\"ko\":\"SITUATIONAL\","
          + "\"scoring\":\"AREA\",\"suicide\":true,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"0\"}";
  private static final String AGA_BUTTON_RULES =
      "{\"friendlyPassOk\":true,\"hasButton\":true,\"ko\":\"SITUATIONAL\","
          + "\"scoring\":\"AREA\",\"suicide\":false,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"N-1\"}";
  private static final String TROMP_TAYLOR_RULES =
      "{\"friendlyPassOk\":false,\"hasButton\":false,\"ko\":\"POSITIONAL\","
          + "\"scoring\":\"AREA\",\"suicide\":true,\"tax\":\"NONE\","
          + "\"whiteHandicapBonus\":\"0\"}";

  private final URI remoteUri;
  private final HttpClient httpClient;
  private final ZhiziGtpTransport.BlockingByteInputStream stdout =
      new ZhiziGtpTransport.BlockingByteInputStream();
  private final ZhiziGtpTransport.BlockingByteInputStream stderr =
      new ZhiziGtpTransport.BlockingByteInputStream();
  private final GtpCommandOutputStream stdin = new GtpCommandOutputStream();
  private final AtomicInteger queryCounter = new AtomicInteger();
  private final AtomicBoolean open = new AtomicBoolean(false);
  private final StringBuilder incomingText = new StringBuilder();
  private final List<Move> initialStones = new ArrayList<>();
  private final List<Move> moves = new ArrayList<>();
  private final Set<String> ignoredQueryIds = new HashSet<>();

  private WebSocket webSocket;
  private int boardWidth = DEFAULT_BOARD_SIZE;
  private int boardHeight = DEFAULT_BOARD_SIZE;
  private double komi = 7.5;
  private String rules = CHINESE_RULES;
  private String activeQueryId = "";
  private String activeGenmoveColor = "";
  private String activeGtpResponseId = "";

  public KataGoAnalysisWebSocketTransport(String remoteUrl) throws IOException {
    this(
        remoteUrl,
        NetworkProxy.configure(HttpClient.newBuilder()).connectTimeout(CONNECT_TIMEOUT).build());
  }

  KataGoAnalysisWebSocketTransport(String remoteUrl, HttpClient httpClient) throws IOException {
    String normalized = RemoteComputeConfig.normalizeCustomWebSocketUrl(remoteUrl);
    if (!RemoteComputeConfig.isCustomWebSocketUrl(normalized)) {
      throw new IOException("自建算力链接必须以 ws:// 或 wss:// 开头。");
    }
    this.remoteUri = URI.create(normalized);
    this.httpClient = httpClient;
  }

  public static KataGoAnalysisWebSocketTransport fromSavedConfig() throws IOException {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    return new KataGoAnalysisWebSocketTransport(state.customRemoteCode);
  }

  @Override
  public void start() throws IOException {
    try {
      webSocket =
          httpClient
              .newWebSocketBuilder()
              .connectTimeout(CONNECT_TIMEOUT)
              .buildAsync(remoteUri, new Listener())
              .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      open.set(true);
      writeStderrLine("自建算力已连接：" + remoteUri);
    } catch (Exception e) {
      open.set(false);
      throw new IOException("连接自建算力失败，请检查 ws/wss 链接是否可用。", e);
    }
  }

  @Override
  public InputStream stdout() {
    return stdout;
  }

  @Override
  public OutputStream stdin() {
    return stdin;
  }

  @Override
  public InputStream stderr() {
    return stderr;
  }

  @Override
  public boolean isOpen() {
    return open.get() && webSocket != null;
  }

  @Override
  public String description() {
    return RemoteComputeConfig.displayNameForCustomWebSocketUrl(remoteUri.toString());
  }

  @Override
  public void close() {
    open.set(false);
    WebSocket current = webSocket;
    webSocket = null;
    if (current != null) {
      try {
        current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      } catch (Exception ignored) {
      }
    }
    closeQuietly(stdout);
    closeQuietly(stderr);
  }

  private synchronized void handleCommandLine(String rawLine) {
    String line = rawLine == null ? "" : rawLine.trim();
    if (line.isEmpty()) {
      return;
    }
    String responseId = "";
    int firstSpace = line.indexOf(' ');
    if (firstSpace > 0 && isPositiveInteger(line.substring(0, firstSpace))) {
      responseId = line.substring(0, firstSpace);
      line = line.substring(firstSpace + 1).trim();
    }
    String lower = line.toLowerCase(Locale.ROOT);
    try {
      if (lower.equals("name")) {
        writeOk(responseId, "KataGo");
      } else if (lower.equals("protocol_version")) {
        writeOk(responseId, "2");
      } else if (lower.equals("version")) {
        writeOk(responseId, "1.16.4");
      } else if (lower.equals("list_commands")) {
        writeOk(
            responseId,
            "protocol_version\nname\nversion\nlist_commands\nboardsize\nrectangular_boardsize\n"
                + "komi\nkata-get-rules\nkata-get-param\nkata-set-param\nkata-set-rules\n"
                + "clear_board\nset_position\nplay\nundo\nkata-analyze\nkata-genmove_analyze\n"
                + "genmove\nkata-time_settings\ntime_settings\nstop\nquit");
      } else if (lower.startsWith("boardsize ")) {
        int size = parseIntToken(line, 1, DEFAULT_BOARD_SIZE);
        boardWidth = Math.max(1, size);
        boardHeight = Math.max(1, size);
        initialStones.clear();
        moves.clear();
        writeOk(responseId, "");
      } else if (lower.startsWith("rectangular_boardsize ")) {
        boardWidth = Math.max(1, parseIntToken(line, 1, DEFAULT_BOARD_SIZE));
        boardHeight = Math.max(1, parseIntToken(line, 2, DEFAULT_BOARD_SIZE));
        initialStones.clear();
        moves.clear();
        writeOk(responseId, "");
      } else if (lower.startsWith("komi ")) {
        komi = parseDoubleToken(line, 1, komi);
        writeOk(responseId, "");
      } else if (lower.startsWith("kata-get-rules")) {
        writeOk(responseId, rules);
      } else if (lower.startsWith("kata-get-param ")) {
        writeKnownKataGoParam(responseId, line);
      } else if (lower.startsWith("kata-set-rules ")) {
        rules = normalizeRules(line.substring(line.indexOf(' ') + 1).trim());
        writeOk(responseId, "");
      } else if (lower.startsWith("kata-set-param ") || lower.startsWith("time_")) {
        writeOk(responseId, "");
      } else if (lower.equals("clear_board")) {
        initialStones.clear();
        moves.clear();
        writeOk(responseId, "");
      } else if (lower.equals("set_position") || lower.startsWith("set_position ")) {
        List<Move> position = parseSetPosition(line);
        initialStones.clear();
        initialStones.addAll(position);
        moves.clear();
        writeOk(responseId, "");
      } else if (lower.startsWith("play ")) {
        String color = normalizeColor(token(line, 1, ""));
        String move = token(line, 2, "");
        if (color.isEmpty() || move.isEmpty()) {
          writeError(responseId, "invalid play command");
        } else {
          moves.add(new Move(color, move));
          writeOk(responseId, "");
        }
      } else if (lower.equals("undo")) {
        if (!moves.isEmpty()) {
          moves.remove(moves.size() - 1);
        }
        writeOk(responseId, "");
      } else if (lower.startsWith("kata-raw-nn")) {
        writeError(responseId, "kata-raw-nn unsupported by websocket analysis adapter");
      } else if (lower.startsWith("kata-analyze")) {
        startAnalysis(responseId, line, false);
      } else if (lower.startsWith("kata-genmove_analyze ") || lower.startsWith("genmove ")) {
        startAnalysis(responseId, line, true);
      } else if (lower.equals("stop") || lower.equals("stop-ponder")) {
        stopActiveQuery();
        writeOk(responseId, "");
      } else if (lower.equals("quit")) {
        stopActiveQuery();
        writeOk(responseId, "");
        close();
      } else {
        writeOk(responseId, "");
      }
    } catch (Exception e) {
      writeError(
          responseId,
          e.getLocalizedMessage() == null ? e.getClass().getSimpleName() : e.getLocalizedMessage());
    }
  }

  private void startAnalysis(String responseId, String command, boolean genmove)
      throws IOException {
    WebSocket current = webSocket;
    if (!open.get() || current == null) {
      throw new IOException("自建算力未连接。");
    }
    stopActiveQuery();
    String queryId = "lz-ws-" + queryCounter.incrementAndGet();
    activeQueryId = queryId;
    activeGtpResponseId = responseId;
    String player = playerToken(command, genmove);
    activeGenmoveColor = genmove ? player : "";
    boolean ownership = command.toLowerCase(Locale.ROOT).contains("ownership true");
    int intervalCentisec = Math.max(10, analyzeIntervalCentisec(command, genmove));
    JSONObject query = buildAnalysisQuery(queryId, genmove, ownership, intervalCentisec, player);
    current.sendText(query.toString(), true);
  }

  private void writeKnownKataGoParam(String responseId, String command) {
    String param = token(command, 1, "");
    if ("playoutDoublingAdvantage".equals(param)) {
      writeOk(responseId, "0.0");
    } else if ("analysisWideRootNoise".equals(param)) {
      writeOk(responseId, "0.04");
    } else {
      writeOk(responseId, "");
    }
  }

  JSONObject buildAnalysisQuery(
      String queryId,
      boolean genmove,
      boolean ownership,
      int intervalCentisec,
      String reportWinrateAs) {
    JSONObject query = new JSONObject();
    query.put("id", queryId);
    String queryRules = rules == null || rules.isBlank() ? CHINESE_RULES : rules;
    query.put("rules", queryRules.startsWith("{") ? new JSONObject(queryRules) : queryRules);
    query.put("priority", genmove ? 2 : 0);
    query.put("analyzeTurns", new JSONArray().put(moves.size()));
    query.put("maxVisits", genmove ? GENMOVE_MAX_VISITS : LIVE_ANALYSIS_MAX_VISITS);
    query.put("komi", komi);
    query.put("boardXSize", boardWidth);
    query.put("boardYSize", boardHeight);
    query.put("includeOwnership", ownership && !genmove);
    query.put("includeMovesOwnership", ownership && !genmove);
    query.put("includePolicy", true);
    JSONArray jsonInitialStones = new JSONArray();
    for (Move stone : initialStones) {
      jsonInitialStones.put(new JSONArray().put(stone.color).put(stone.point));
    }
    query.put("initialStones", jsonInitialStones);
    query.put("initialPlayer", moves.isEmpty() ? reportWinrateAs : moves.get(0).color);
    JSONArray jsonMoves = new JSONArray();
    for (Move move : moves) {
      jsonMoves.put(new JSONArray().put(move.color).put(move.point));
    }
    query.put("moves", jsonMoves);
    JSONObject overrides = new JSONObject();
    overrides.put("reportAnalysisWinratesAs", "B".equals(reportWinrateAs) ? "BLACK" : "WHITE");
    query.put("overrideSettings", overrides);
    if (!genmove) {
      query.put("reportDuringSearchEvery", Math.max(0.1, intervalCentisec / 100.0));
    }
    return query;
  }

  private String normalizeRules(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.startsWith("{")) {
      new JSONObject(trimmed);
      return trimmed;
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT);
    if ("chinese".equals(normalized)) {
      return CHINESE_RULES;
    }
    if ("chinese-ogs".equals(normalized) || "chinese-kgs".equals(normalized)) {
      return CHINESE_POSITIONAL_RULES;
    }
    if ("japanese".equals(normalized) || "korean".equals(normalized)) {
      return JAPANESE_RULES;
    }
    if ("stone-scoring".equals(normalized)) {
      return STONE_SCORING_RULES;
    }
    if ("aga".equals(normalized) || "bga".equals(normalized)) {
      return AGA_RULES;
    }
    if ("new-zealand".equals(normalized)) {
      return NEW_ZEALAND_RULES;
    }
    if ("aga-button".equals(normalized)) {
      return AGA_BUTTON_RULES;
    }
    if ("tromp-taylor".equals(normalized) || "tromp_taylor".equals(normalized)) {
      return TROMP_TAYLOR_RULES;
    }
    throw new IllegalArgumentException("unsupported rules: " + value);
  }

  private List<Move> parseSetPosition(String line) {
    String[] tokens = line.trim().split("\\s+");
    if ((tokens.length - 1) % 2 != 0) {
      throw new IllegalArgumentException("invalid set_position command");
    }
    List<Move> position = new ArrayList<>();
    Set<String> occupied = new HashSet<>();
    for (int i = 1; i < tokens.length; i += 2) {
      String color = normalizeColor(tokens[i]);
      String point = tokens[i + 1].toUpperCase(Locale.ROOT);
      if (color.isEmpty() || !isBoardPoint(point) || !occupied.add(point)) {
        throw new IllegalArgumentException("invalid set_position command");
      }
      position.add(new Move(color, point));
    }
    if (containsZeroLibertyGroup(position)) {
      throw new IllegalArgumentException("invalid set_position command");
    }
    return position;
  }

  private boolean containsZeroLibertyGroup(List<Move> position) {
    String[][] colors = new String[boardWidth][boardHeight];
    for (Move stone : position) {
      int[] coordinates = pointCoordinates(stone.point);
      colors[coordinates[0]][coordinates[1]] = stone.color;
    }
    boolean[][] visited = new boolean[boardWidth][boardHeight];
    int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int x = 0; x < boardWidth; x++) {
      for (int y = 0; y < boardHeight; y++) {
        if (colors[x][y] == null || visited[x][y]) {
          continue;
        }
        String color = colors[x][y];
        boolean hasLiberty = false;
        ArrayDeque<Integer> group = new ArrayDeque<>();
        group.addLast(y * boardWidth + x);
        visited[x][y] = true;
        while (!group.isEmpty()) {
          int point = group.removeFirst();
          int pointX = point % boardWidth;
          int pointY = point / boardWidth;
          for (int[] direction : directions) {
            int adjacentX = pointX + direction[0];
            int adjacentY = pointY + direction[1];
            if (adjacentX < 0
                || adjacentX >= boardWidth
                || adjacentY < 0
                || adjacentY >= boardHeight) {
              continue;
            }
            String adjacentColor = colors[adjacentX][adjacentY];
            if (adjacentColor == null) {
              hasLiberty = true;
            } else if (color.equals(adjacentColor) && !visited[adjacentX][adjacentY]) {
              visited[adjacentX][adjacentY] = true;
              group.addLast(adjacentY * boardWidth + adjacentX);
            }
          }
        }
        if (!hasLiberty) {
          return true;
        }
      }
    }
    return false;
  }

  private int[] pointCoordinates(String point) {
    char columnName = point.charAt(0);
    int column = columnName - 'A' - (columnName > 'I' ? 1 : 0);
    return new int[] {column, Integer.parseInt(point.substring(1)) - 1};
  }

  private boolean isBoardPoint(String point) {
    if (point == null || point.length() < 2) {
      return false;
    }
    char columnName = point.charAt(0);
    if (columnName < 'A' || columnName > 'Z' || columnName == 'I') {
      return false;
    }
    try {
      int[] coordinates = pointCoordinates(point);
      int column = coordinates[0];
      int row = coordinates[1] + 1;
      return column >= 0 && column < boardWidth && row >= 1 && row <= boardHeight;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private synchronized void handleWebSocketMessage(String text) {
    for (String line : splitJsonLines(text)) {
      if (line.isBlank()) {
        continue;
      }
      JSONObject response;
      try {
        response = new JSONObject(line);
      } catch (Exception e) {
        writeStderrLine("自建算力返回了无法解析的 JSON：" + summarize(line));
        continue;
      }
      String queryId = response.optString("id", "");
      if (queryId.isEmpty() || ignoredQueryIds.remove(queryId)) {
        continue;
      }
      if (!queryId.equals(activeQueryId)) {
        continue;
      }
      if (response.has("error")) {
        writeStderrLine("自建算力错误：" + response.optString("error"));
        activeQueryId = "";
        activeGenmoveColor = "";
        continue;
      }
      String infoLine = toGtpInfoLine(response);
      if (!infoLine.isEmpty()) {
        appendStdout(infoLine + "\n");
      }
      boolean partial = response.optBoolean("isDuringSearch", false);
      if (!partial && !activeGenmoveColor.isEmpty()) {
        String move = firstMove(response);
        if (!move.isEmpty()) {
          moves.add(new Move(activeGenmoveColor, move));
          writeOk(activeGtpResponseId, move);
        }
        activeGenmoveColor = "";
        activeQueryId = "";
        activeGtpResponseId = "";
      } else if (!partial && activeQueryId.equals(queryId)) {
        activeQueryId = "";
        activeGtpResponseId = "";
      }
    }
  }

  static String toGtpInfoLine(JSONObject response) {
    JSONArray moveInfos = response.optJSONArray("moveInfos");
    if (moveInfos == null || moveInfos.length() == 0) {
      return "";
    }
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < moveInfos.length(); i++) {
      JSONObject move = moveInfos.optJSONObject(i);
      if (move == null) {
        continue;
      }
      if (line.length() > 0) {
        line.append(" info ");
      } else {
        line.append("info ");
      }
      appendMoveInfo(line, move, i);
    }
    JSONArray ownership = response.optJSONArray("ownership");
    if (ownership != null && ownership.length() > 0) {
      line.append(" ownership");
      int limit = Math.min(ownership.length(), 19 * 19);
      for (int i = 0; i < limit; i++) {
        line.append(' ').append(formatDouble(ownership.optDouble(i, 0.0)));
      }
    }
    return line.toString();
  }

  private static void appendMoveInfo(StringBuilder line, JSONObject move, int fallbackOrder) {
    line.append("move ").append(move.optString("move", "pass"));
    line.append(" visits ").append(Math.max(0, move.optInt("visits", 0)));
    if (move.has("winrate")) {
      line.append(" winrate ").append(formatDouble(move.optDouble("winrate", 0.5)));
    }
    if (move.has("scoreLead")) {
      line.append(" scoreLead ").append(formatDouble(move.optDouble("scoreLead", 0.0)));
    } else if (move.has("scoreMean")) {
      line.append(" scoreLead ").append(formatDouble(move.optDouble("scoreMean", 0.0)));
    }
    if (move.has("prior")) {
      line.append(" prior ").append(formatDouble(move.optDouble("prior", 0.0)));
    }
    if (move.has("lcb")) {
      line.append(" lcb ").append(formatDouble(move.optDouble("lcb", 0.0)));
    }
    line.append(" order ").append(move.optInt("order", fallbackOrder));
    JSONArray pv = move.optJSONArray("pv");
    if (pv != null && pv.length() > 0) {
      line.append(" pv");
      for (int i = 0; i < pv.length(); i++) {
        String point = pv.optString(i, "");
        if (!point.isBlank()) {
          line.append(' ').append(point);
        }
      }
    }
  }

  private static String firstMove(JSONObject response) {
    JSONArray moveInfos = response.optJSONArray("moveInfos");
    if (moveInfos == null || moveInfos.length() == 0) {
      return "";
    }
    JSONObject move = moveInfos.optJSONObject(0);
    return move == null ? "" : move.optString("move", "");
  }

  private void stopActiveQuery() {
    if (activeQueryId == null || activeQueryId.isEmpty()) {
      return;
    }
    ignoredQueryIds.add(activeQueryId);
    WebSocket current = webSocket;
    if (current != null && open.get()) {
      JSONObject terminate = new JSONObject();
      terminate.put("id", "terminate-" + activeQueryId);
      terminate.put("action", "terminate");
      terminate.put("terminateId", activeQueryId);
      try {
        current.sendText(terminate.toString(), true);
      } catch (Exception ignored) {
      }
    }
    activeQueryId = "";
    activeGenmoveColor = "";
    activeGtpResponseId = "";
  }

  private String nextPlayer() {
    return moves.size() % 2 == 0 ? "B" : "W";
  }

  private String playerToken(String command, boolean genmove) {
    String player = normalizeColor(token(command, 1, ""));
    return player.isEmpty() ? nextPlayer() : player;
  }

  private int analyzeIntervalCentisec(String command, boolean genmove) {
    int index = normalizeColor(token(command, 1, "")).isEmpty() ? 1 : 2;
    return parseIntToken(command, index, genmove ? 50 : 50);
  }

  private void writeOk(String responseId, String body) {
    appendStdout(
        "="
            + safeResponseId(responseId)
            + (body == null || body.isEmpty() ? "" : " " + body)
            + "\n\n");
  }

  private void writeError(String responseId, String body) {
    appendStdout("?" + safeResponseId(responseId) + " " + (body == null ? "error" : body) + "\n\n");
  }

  private void appendStdout(String text) {
    stdout.append(text.getBytes(StandardCharsets.UTF_8));
  }

  private void writeStderrLine(String line) {
    stderr.append(("[remote-ws] " + line + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static String safeResponseId(String responseId) {
    return responseId == null || responseId.isBlank() ? "" : responseId;
  }

  private static boolean isPositiveInteger(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static int parseIntToken(String command, int index, int fallback) {
    try {
      return Integer.parseInt(token(command, index, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double parseDoubleToken(String command, int index, double fallback) {
    try {
      return Double.parseDouble(token(command, index, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String token(String command, int index, String fallback) {
    if (command == null) {
      return fallback;
    }
    String[] tokens = command.trim().split("\\s+");
    return index >= 0 && index < tokens.length ? tokens[index] : fallback;
  }

  private static String normalizeColor(String color) {
    if (color == null || color.isBlank()) {
      return "";
    }
    String upper = color.trim().substring(0, 1).toUpperCase(Locale.ROOT);
    return "B".equals(upper) || "W".equals(upper) ? upper : "";
  }

  private static List<String> splitJsonLines(String text) {
    List<String> lines = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return lines;
    }
    for (String line : text.split("\\R")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    return lines;
  }

  private static String formatDouble(double value) {
    return String.format(Locale.US, "%.6f", value);
  }

  private static String summarize(String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      if (closeable != null) {
        closeable.close();
      }
    } catch (Exception ignored) {
    }
  }

  private final class GtpCommandOutputStream extends OutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int b) {
      if (b == '\n') {
        flushLine();
      } else if (b != '\r') {
        buffer.write(b);
      }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      if (b == null) {
        return;
      }
      for (int i = off; i < off + len; i++) {
        write(b[i]);
      }
    }

    @Override
    public synchronized void flush() {
      flushLine();
    }

    private void flushLine() {
      if (buffer.size() == 0) {
        return;
      }
      String line = buffer.toString(StandardCharsets.UTF_8);
      buffer.reset();
      handleCommandLine(line);
    }
  }

  private final class Listener implements WebSocket.Listener {
    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      synchronized (incomingText) {
        incomingText.append(data);
        if (last) {
          String message = incomingText.toString();
          incomingText.setLength(0);
          handleWebSocketMessage(message);
        }
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      open.set(false);
      writeStderrLine("自建算力连接已断开：" + statusCode + " " + reason);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      open.set(false);
      writeStderrLine("自建算力连接错误：" + (error == null ? "unknown" : error.getMessage()));
    }
  }

  private static final class Move {
    private final String color;
    private final String point;

    private Move(String color, String point) {
      this.color = color;
      this.point = point == null || point.isBlank() ? "pass" : point;
    }
  }
}
