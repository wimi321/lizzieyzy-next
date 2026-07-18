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
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private static final String SGF_COORD_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Pattern SNAPSHOT_SGF_PATTERN =
      Pattern.compile(
          "^\\(;FF\\[4\\]GM\\[1\\]CA\\[UTF-8\\]SZ\\[(\\d+(?::\\d+)?)\\]"
              + "(?:KM\\[([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\])?PL\\[([BW])\\]"
              + "((?:AB\\[[^\\]]+\\])*(?:AW\\[[^\\]]+\\])*)\\)$");
  private static final Pattern SNAPSHOT_STONE_PATTERN = Pattern.compile("(AB|AW)\\[([^\\]]+)\\]");

  private final URI remoteUri;
  private final HttpClient httpClient;
  private final ZhiziGtpTransport.BlockingByteInputStream stdout =
      new ZhiziGtpTransport.BlockingByteInputStream();
  private final ZhiziGtpTransport.BlockingByteInputStream stderr =
      new ZhiziGtpTransport.BlockingByteInputStream();
  private final GtpCommandOutputStream stdin = new GtpCommandOutputStream();
  private final AtomicLong queryCounter = new AtomicLong();
  private final AtomicBoolean open = new AtomicBoolean(false);
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final StringBuilder incomingText = new StringBuilder();
  private final List<Move> initialStones = new ArrayList<>();
  private final List<Move> moves = new ArrayList<>();

  private WebSocket webSocket;
  private int boardWidth = DEFAULT_BOARD_SIZE;
  private int boardHeight = DEFAULT_BOARD_SIZE;
  private double komi = 7.5;
  private String rules = CHINESE_RULES;
  private ActiveQuery activeQuery;
  private String snapshotInitialPlayer = "";

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
      WebSocket connected =
          httpClient
              .newWebSocketBuilder()
              .connectTimeout(CONNECT_TIMEOUT)
              .buildAsync(remoteUri, new Listener())
              .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      synchronized (this) {
        if (terminated.get()) {
          try {
            connected.sendClose(WebSocket.NORMAL_CLOSURE, "closed during connect");
          } catch (Exception ignored) {
          }
          throw new IOException("自建算力在连接完成前已断开。");
        }
        webSocket = connected;
        open.set(true);
        writeStderrLine("自建算力已连接：" + remoteUri);
      }
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
    terminateTransport(true);
  }

  private synchronized void terminateTransport(boolean sendClose) {
    if (!terminated.compareAndSet(false, true)) {
      return;
    }
    open.set(false);
    ActiveQuery query = activeQuery;
    if (query != null) {
      completeActiveQuery(query);
    }
    WebSocket current = webSocket;
    webSocket = null;
    if (sendClose && current != null) {
      try {
        current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      } catch (Exception ignored) {
      }
    }
    stdout.finish();
    stderr.finish();
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
                + "clear_board\nset_position\nloadsgf\nplay\nundo\nkata-analyze\n"
                + "kata-genmove_analyze\n"
                + "genmove\nkata-time_settings\ntime_settings\nstop\nquit");
      } else if (lower.startsWith("boardsize ")) {
        int size = parseIntToken(line, 1, DEFAULT_BOARD_SIZE);
        boardWidth = Math.max(1, size);
        boardHeight = Math.max(1, size);
        initialStones.clear();
        moves.clear();
        snapshotInitialPlayer = "";
        writeOk(responseId, "");
      } else if (lower.startsWith("rectangular_boardsize ")) {
        boardWidth = Math.max(1, parseIntToken(line, 1, DEFAULT_BOARD_SIZE));
        boardHeight = Math.max(1, parseIntToken(line, 2, DEFAULT_BOARD_SIZE));
        initialStones.clear();
        moves.clear();
        snapshotInitialPlayer = "";
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
      } else if (lower.startsWith("kata-set-param ")
          || lower.startsWith("time_settings ")
          || lower.startsWith("time_left ")
          || lower.startsWith("kata-time_settings ")) {
        writeOk(responseId, "");
      } else if (lower.equals("clear_board")) {
        initialStones.clear();
        moves.clear();
        snapshotInitialPlayer = "";
        writeOk(responseId, "");
      } else if (lower.equals("set_position") || lower.startsWith("set_position ")) {
        List<Move> position = parseSetPosition(line);
        initialStones.clear();
        initialStones.addAll(position);
        moves.clear();
        snapshotInitialPlayer = "";
        writeOk(responseId, "");
      } else if (lower.startsWith("loadsgf ")) {
        loadSnapshotSgf(line.substring(line.indexOf(' ') + 1).trim());
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
        requestStop(responseId);
      } else if (lower.equals("quit")) {
        writeOk(responseId, "");
        close();
      } else {
        writeError(responseId, "unknown command");
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
    if (activeQuery != null) {
      throw new IOException("another websocket analysis query is still active");
    }
    long querySequence = queryCounter.incrementAndGet();
    String queryId = "lz-ws-" + querySequence;
    String player = playerToken(command, genmove);
    ActiveQuery queryContext =
        new ActiveQuery(querySequence, queryId, responseId, genmove, player);
    activeQuery = queryContext;
    boolean ownership = command.toLowerCase(Locale.ROOT).contains("ownership true");
    int intervalCentisec = Math.max(10, analyzeIntervalCentisec(command, genmove));
    JSONObject query = buildAnalysisQuery(queryId, genmove, ownership, intervalCentisec, player);
    try {
      current
          .sendText(query.toString(), true)
          .whenComplete((ignored, failure) -> completeAnalysisSend(queryContext, failure));
    } catch (RuntimeException failure) {
      failActiveQuery(queryContext, "发送分析请求失败：" + summarize(failure.getMessage()));
    }
  }

  private synchronized void completeAnalysisSend(ActiveQuery query, Throwable failure) {
    if (activeQuery != query || query.completed) {
      return;
    }
    if (failure != null) {
      failActiveQuery(query, "发送分析请求失败：" + summarize(failure.getMessage()));
      return;
    }
    query.sendCompleted = true;
    while (!query.pendingResponses.isEmpty()
        && !isAnalysisPayload(query, query.pendingResponses.get(0))) {
      processWebSocketResponse(query.pendingResponses.remove(0));
      if (activeQuery != query || query.completed) {
        return;
      }
    }
    if (!query.genmove) {
      appendStdout("=" + safeResponseId(query.gtpResponseId) + "\n");
      query.responseStarted = true;
    }
    while (!query.pendingResponses.isEmpty() && activeQuery == query && !query.completed) {
      processWebSocketResponse(query.pendingResponses.remove(0));
    }
  }

  private void writeKnownKataGoParam(String responseId, String command) {
    String param = token(command, 1, "");
    if ("playoutDoublingAdvantage".equals(param)) {
      writeOk(responseId, "0.0");
    } else if ("analysisWideRootNoise".equals(param)) {
      writeOk(responseId, "0.04");
    } else {
      writeError(responseId, "unknown parameter");
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
    query.put(
        "initialPlayer",
        moves.isEmpty()
            ? (snapshotInitialPlayer.isEmpty() ? reportWinrateAs : snapshotInitialPlayer)
            : moves.get(0).color);
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

  private void loadSnapshotSgf(String fileName) throws IOException {
    if (fileName.isEmpty()) {
      throw new IllegalArgumentException("loadsgf requires a snapshot file");
    }
    SnapshotPosition snapshot = parseSnapshotSgf(Files.readString(Path.of(fileName)));
    boardWidth = snapshot.width;
    boardHeight = snapshot.height;
    if (snapshot.komi != null) {
      komi = snapshot.komi;
    }
    initialStones.clear();
    initialStones.addAll(snapshot.stones);
    moves.clear();
    snapshotInitialPlayer = snapshot.player;
  }

  private SnapshotPosition parseSnapshotSgf(String sgf) {
    Matcher matcher = SNAPSHOT_SGF_PATTERN.matcher(sgf == null ? "" : sgf.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("unsupported loadsgf snapshot");
    }
    int[] size = parseSnapshotBoardSize(matcher.group(1));
    Double snapshotKomi = matcher.group(2) == null ? null : Double.valueOf(matcher.group(2));
    List<Move> stones = new ArrayList<>();
    Set<String> occupied = new HashSet<>();
    Matcher stoneMatcher = SNAPSHOT_STONE_PATTERN.matcher(matcher.group(4));
    while (stoneMatcher.find()) {
      String point = snapshotPoint(stoneMatcher.group(2), size[0], size[1]);
      if (!occupied.add(point)) {
        throw new IllegalArgumentException("invalid loadsgf snapshot");
      }
      stones.add(new Move("AB".equals(stoneMatcher.group(1)) ? "B" : "W", point));
    }
    return new SnapshotPosition(size[0], size[1], snapshotKomi, matcher.group(3), stones);
  }

  private int[] parseSnapshotBoardSize(String value) {
    String[] dimensions = value.split(":", -1);
    if (dimensions.length > 2) {
      throw new IllegalArgumentException("invalid loadsgf board size");
    }
    int width = Integer.parseInt(dimensions[0]);
    int height = dimensions.length == 1 ? width : Integer.parseInt(dimensions[1]);
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("invalid loadsgf board size");
    }
    return new int[] {width, height};
  }

  private String snapshotPoint(String value, int width, int height) {
    int x;
    int y;
    if (width >= 52 || height >= 52) {
      String[] coordinates = value.split("_", -1);
      if (coordinates.length != 2) {
        throw new IllegalArgumentException("invalid loadsgf coordinate");
      }
      x = Integer.parseInt(coordinates[0]);
      y = Integer.parseInt(coordinates[1]);
    } else {
      if (value.length() != 2) {
        throw new IllegalArgumentException("invalid loadsgf coordinate");
      }
      x = SGF_COORD_ALPHABET.indexOf(value.charAt(0));
      y = SGF_COORD_ALPHABET.indexOf(value.charAt(1));
    }
    if (x < 0 || x >= width || y < 0 || y >= height) {
      throw new IllegalArgumentException("invalid loadsgf coordinate");
    }
    if (width > 25 || height > 25) {
      return "(" + x + "," + y + ")";
    }
    char column = (char) ('A' + x + (x >= 8 ? 1 : 0));
    return String.valueOf(column) + (height - y);
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
        failProtocol("自建算力返回了无法解析的 JSON：" + summarize(line));
        return;
      }
      ActiveQuery query = activeQuery;
      if (isRetiredQueryId(response.optString("id", ""), query)) {
        continue;
      }
      if (query != null && !query.sendCompleted) {
        query.pendingResponses.add(response);
        continue;
      }
      processWebSocketResponse(response);
    }
  }

  private void processWebSocketResponse(JSONObject response) {
    String queryId = response.optString("id", "");
    ActiveQuery query = activeQuery;
    if (response.has("error")) {
      if (query != null) {
        failActiveQuery(query, "自建算力错误：" + response.optString("error"));
      } else {
        failProtocol("自建算力错误：" + response.optString("error"));
      }
      return;
    }
    if (query == null) {
      failProtocol("自建算力返回了没有活动查询的响应：" + summarize(response.toString()));
      return;
    }
    if (queryId.isEmpty()) {
      failActiveQuery(query, "自建算力响应缺少查询 ID。");
      return;
    }
    if (queryId.equals(query.terminateQueryId)) {
      if (!"terminate".equals(response.optString("action", ""))
          || !query.queryId.equals(response.optString("terminateId", ""))) {
        failActiveQuery(query, "自建算力返回了不完整的停止回执。");
        return;
      }
      query.terminateAcknowledged = true;
      completeStoppedQueryIfReady(query);
      return;
    }
    if (!queryId.equals(query.queryId)) {
      failActiveQuery(query, "自建算力返回了错误的查询 ID：" + summarize(queryId));
      return;
    }
    if (response.has("warning")) {
      writeStderrLine("自建算力警告：" + response.optString("warning"));
      return;
    }
    if (!query.sendCompleted || (!query.genmove && !query.responseStarted)) {
      failActiveQuery(query, "自建算力在查询发送完成前返回了响应。");
      return;
    }
    if (query.analysisFinalReceived) {
      return;
    }
    Object duringSearch = response.opt("isDuringSearch");
    if (!(duringSearch instanceof Boolean)) {
      failActiveQuery(query, "自建算力分析响应缺少 isDuringSearch。");
      return;
    }
    String infoLine = toGtpInfoLine(response);
    if (!infoLine.isEmpty()) {
      appendStdout(infoLine + "\n");
    }
    boolean partial = (Boolean) duringSearch;
    if (!partial && query.genmove) {
      String move = firstMove(response);
      if (!move.isEmpty()) {
        moves.add(new Move(query.genmoveColor, move));
        writeOk(query.gtpResponseId, move);
      }
      query.analysisFinalReceived = true;
      completeStoppedQueryIfReady(query);
      if (!query.stopRequested) {
        completeActiveQuery(query);
      }
    } else if (!partial && !query.analysisFinalReceived) {
      appendStdout("\n");
      query.analysisFinalReceived = true;
      completeStoppedQueryIfReady(query);
      if (!query.stopRequested) {
        completeActiveQuery(query);
      }
    }
  }

  private static boolean isAnalysisPayload(ActiveQuery query, JSONObject response) {
    return !response.has("error")
        && !response.has("warning")
        && query.queryId.equals(response.optString("id", ""))
        && response.opt("isDuringSearch") instanceof Boolean;
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

  private void requestStop(String responseId) {
    ActiveQuery query = activeQuery;
    if (query == null) {
      writeOk(responseId, "");
      return;
    }
    if (query.stopRequested) {
      return;
    }
    query.stopRequested = true;
    query.stopGtpResponseId = responseId;
    query.terminateQueryId = "terminate-" + query.queryId;
    WebSocket current = webSocket;
    if (current != null && open.get()) {
      JSONObject terminate = new JSONObject();
      terminate.put("id", query.terminateQueryId);
      terminate.put("action", "terminate");
      terminate.put("terminateId", query.queryId);
      try {
        current
            .sendText(terminate.toString(), true)
            .whenComplete((ignored, failure) -> completeTerminateSend(query, failure));
      } catch (RuntimeException failure) {
        completeTerminateSend(query, failure);
      }
    } else {
      completeTerminateSend(query, new IOException("websocket transport is closed"));
    }
  }

  private synchronized void completeTerminateSend(ActiveQuery query, Throwable failure) {
    if (failure == null || activeQuery != query || query.completed) {
      return;
    }
    failActiveQuery(query, "发送停止请求失败：" + summarize(failure.getMessage()));
  }

  private void failActiveQuery(ActiveQuery query, String diagnostic) {
    if (activeQuery != query || query.completed) {
      return;
    }
    writeStderrLine(diagnostic);
    if (!query.responseStarted) {
      writeError(query.gtpResponseId, diagnostic);
    }
    completeActiveQuery(query);
    terminateTransport(true);
  }

  private void failProtocol(String diagnostic) {
    ActiveQuery query = activeQuery;
    if (query != null) {
      failActiveQuery(query, diagnostic);
      return;
    }
    writeStderrLine(diagnostic);
    terminateTransport(true);
  }

  private void completeStoppedQueryIfReady(ActiveQuery query) {
    if (activeQuery != query
        || query.completed
        || !query.stopRequested
        || !query.analysisFinalReceived
        || !query.terminateAcknowledged) {
      return;
    }
    writeOk(query.stopGtpResponseId, "");
    completeActiveQuery(query);
  }

  private void completeActiveQuery(ActiveQuery query) {
    if (activeQuery != query || query.completed) {
      return;
    }
    query.completed = true;
    activeQuery = null;
  }

  private boolean isRetiredQueryId(String queryId, ActiveQuery query) {
    long sequence = querySequence(queryId);
    if (sequence <= 0) {
      return false;
    }
    return query == null ? sequence <= queryCounter.get() : sequence < query.sequence;
  }

  private static long querySequence(String queryId) {
    String normalized = queryId;
    if (normalized.startsWith("terminate-")) {
      normalized = normalized.substring("terminate-".length());
    }
    if (!normalized.startsWith("lz-ws-")) {
      return -1;
    }
    try {
      return Long.parseLong(normalized.substring("lz-ws-".length()));
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private String nextPlayer() {
    String firstPlayer = snapshotInitialPlayer.isEmpty() ? "B" : snapshotInitialPlayer;
    return moves.size() % 2 == 0 ? firstPlayer : ("B".equals(firstPlayer) ? "W" : "B");
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

  private static final class SnapshotPosition {
    private final int width;
    private final int height;
    private final Double komi;
    private final String player;
    private final List<Move> stones;

    private SnapshotPosition(int width, int height, Double komi, String player, List<Move> stones) {
      this.width = width;
      this.height = height;
      this.komi = komi;
      this.player = player;
      this.stones = stones;
    }
  }

  private static final class ActiveQuery {
    private final long sequence;
    private final String queryId;
    private final String gtpResponseId;
    private final boolean genmove;
    private final String genmoveColor;
    private String terminateQueryId = "";
    private String stopGtpResponseId = "";
    private final List<JSONObject> pendingResponses = new ArrayList<>();
    private boolean sendCompleted;
    private boolean responseStarted;
    private boolean stopRequested;
    private boolean analysisFinalReceived;
    private boolean terminateAcknowledged;
    private boolean completed;

    private ActiveQuery(
        long sequence,
        String queryId,
        String gtpResponseId,
        boolean genmove,
        String genmoveColor) {
      this.sequence = sequence;
      this.queryId = queryId;
      this.gtpResponseId = gtpResponseId;
      this.genmove = genmove;
      this.genmoveColor = genmoveColor;
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
      writeStderrLine("自建算力连接已断开：" + statusCode + " " + reason);
      terminateTransport(false);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      writeStderrLine("自建算力连接错误：" + (error == null ? "unknown" : error.getMessage()));
      terminateTransport(false);
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
