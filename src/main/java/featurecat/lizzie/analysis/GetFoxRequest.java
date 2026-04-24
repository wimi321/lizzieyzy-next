package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.FoxKifuDownload;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class GetFoxRequest {
  private static final String BASE_URL = "https://h5.foxwq.com/yehuDiamond/chessbook_local";
  private static final String QUERY_USER_URL = "https://newframe.foxwq.com/cgi/QueryUserInfoPanel";
  private static final String[] FOX_SGF_CGI_URLS = {
    "http://happyapp.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChess",
    "http://cgi.foxwq.com/cgi-bin/CommonMobileCGI/TXWQFetchChess"
  };
  private static final int HTTP_CONNECT_TIMEOUT_MS = 20000;
  private static final int HTTP_READ_TIMEOUT_MS = 25000;
  private static final int HTTP_MAX_RETRIES = 3;
  private static final String MOBILE_USER_AGENT =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
  private static final String CGI_USER_AGENT = "okhttp/3.12.12";

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
    String cgiPayload = fetchSgfFromCgi(chessid);
    if (!cgiPayload.isEmpty()) {
      return normalizeFoxSgfPayload(cgiPayload);
    }
    return normalizeFoxSgfPayload(httpGet(BASE_URL + "/YHWQFetchChess?chessid=" + url(chessid)));
  }

  private String fetchSgfFromCgi(String chessid) {
    String safeChessId = chessid == null ? "" : chessid.trim();
    if (safeChessId.isEmpty()) {
      return "";
    }
    LinkedHashMap<String, String> form = new LinkedHashMap<String, String>();
    form.put("chessid", safeChessId);
    for (String endpoint : FOX_SGF_CGI_URLS) {
      try {
        String response = httpPostForm(endpoint, form, CGI_USER_AGENT);
        JSONObject json = new JSONObject(response);
        if (json.optInt("result", -1) == 0 && !json.optString("chess", "").trim().isEmpty()) {
          return response;
        }
      } catch (Exception e) {
        // Fall through to the next endpoint or the legacy H5 fallback.
      }
    }
    return "";
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
    return httpRequest("GET", url, null, null, MOBILE_USER_AGENT);
  }

  private String httpPostForm(String url, LinkedHashMap<String, String> form, String userAgent) {
    StringBuilder body = new StringBuilder();
    boolean first = true;
    for (java.util.Map.Entry<String, String> entry : form.entrySet()) {
      if (!first) {
        body.append('&');
      }
      first = false;
      body.append(url(entry.getKey())).append('=').append(url(entry.getValue()));
    }
    return httpRequest(
        "POST",
        url,
        body.toString().getBytes(StandardCharsets.UTF_8),
        "application/x-www-form-urlencoded",
        userAgent);
  }

  private String httpRequest(
      String method, String url, byte[] body, String contentType, String userAgent) {
    IOException lastError = null;
    for (int attempt = 1; attempt <= HTTP_MAX_RETRIES; attempt++) {
      java.net.HttpURLConnection conn = null;
      try {
        conn = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", userAgent == null ? MOBILE_USER_AGENT : userAgent);
        if (contentType != null && !contentType.trim().isEmpty()) {
          conn.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
          conn.setDoOutput(true);
          conn.setFixedLengthStreamingMode(body.length);
          conn.getOutputStream().write(body);
          conn.getOutputStream().flush();
          conn.getOutputStream().close();
        }
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
      return new SgfMainLineNormalizer(sgf).normalize();
    } catch (RuntimeException e) {
      return sgf;
    }
  }

  private static final class SgfMainLineNormalizer {
    private static final List<String> ROOT_PROPERTY_ORDER =
        Arrays.asList(
            "GM", "FF", "CA", "AP", "ST", "RU", "SZ", "KM", "HA", "TM", "TC", "TT", "OT", "EV",
            "RO", "PC", "DT", "GN", "GC", "PB", "BR", "PW", "WR", "RE", "US", "SO", "CP", "AN",
            "ON", "BT", "WT", "PL", "AB", "AW", "AE", "RN", "RL");

    private static final LinkedHashSet<String> ROOT_PROPERTIES =
        new LinkedHashSet<String>(ROOT_PROPERTY_ORDER);

    private static final LinkedHashSet<String> ROOT_MULTI_VALUE_PROPERTIES =
        new LinkedHashSet<String>(Arrays.asList("AB", "AW", "AE"));

    private final String input;
    private int index = 0;

    private SgfMainLineNormalizer(String input) {
      this.input = input;
    }

    private String normalize() {
      skipWhitespace();
      if (index >= input.length() || input.charAt(index) != '(') {
        return input;
      }
      SgfTree tree = parseTree();
      skipWhitespace();
      if (index < input.length()) {
        return input;
      }
      return rebuild(tree);
    }

    private String rebuild(SgfTree tree) {
      if (tree.nodes.isEmpty()) {
        return input;
      }
      SgfNode rootNode = tree.nodes.get(0);
      List<SgfProperty> rootProperties = buildRootProperties(tree.nodes);
      List<SgfMove> rootMoves = extractMoves(rootNode.properties);
      List<SgfMove> mainLineMoves = recoverPreferredMoves(tree, rootMoves);
      String nextColor = mainLineMoves.isEmpty() ? null : mainLineMoves.get(0).color;
      List<SgfMove> rootPrefix = chooseCompatibleRootPrefix(rootMoves, nextColor);
      List<SgfMove> moves = normalizeMoves(rootPrefix, mainLineMoves);

      if (rootProperties.isEmpty() && moves.isEmpty()) {
        return input;
      }

      return buildSgf(rootProperties, moves);
    }

    private String buildSgf(List<SgfProperty> rootProperties, List<SgfMove> moves) {
      StringBuilder out = new StringBuilder(Math.max(128, input.length() / 8));
      out.append('(').append(';');
      appendProperties(out, rootProperties);
      for (SgfMove move : moves) {
        out.append(';').append(move.color).append('[').append(move.coordinate).append(']');
      }
      out.append(')');
      return out.toString();
    }

    private SgfTree parseTree() {
      expect('(');
      skipWhitespace();
      List<SgfNode> nodes = new ArrayList<SgfNode>();
      while (index < input.length() && input.charAt(index) == ';') {
        nodes.add(parseNode());
        skipWhitespace();
      }
      List<SgfTree> children = new ArrayList<SgfTree>();
      while (index < input.length() && input.charAt(index) == '(') {
        children.add(parseTree());
        skipWhitespace();
      }
      expect(')');
      return new SgfTree(nodes, children);
    }

    private SgfNode parseNode() {
      expect(';');
      List<SgfProperty> properties = new ArrayList<SgfProperty>();
      while (true) {
        skipWhitespace();
        if (index >= input.length()) {
          break;
        }
        char current = input.charAt(index);
        if (current == ';' || current == '(' || current == ')') {
          break;
        }
        properties.add(parseProperty());
      }
      return new SgfNode(properties);
    }

    private SgfProperty parseProperty() {
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
      String name = input.substring(nameStart, index);
      skipWhitespace();
      List<String> values = new ArrayList<String>();
      while (index < input.length() && input.charAt(index) == '[') {
        values.add(parseValue());
        skipWhitespace();
      }
      if (values.isEmpty()) {
        throw new IllegalStateException("Missing SGF property value");
      }
      return new SgfProperty(name, values);
    }

    private String parseValue() {
      expect('[');
      StringBuilder value = new StringBuilder();
      while (index < input.length()) {
        char current = input.charAt(index++);
        if (current == '\\') {
          if (index < input.length()) {
            value.append(current).append(input.charAt(index++));
          }
        } else if (current == ']') {
          return value.toString();
        } else {
          value.append(current);
        }
      }
      throw new IllegalStateException("Unterminated SGF value");
    }

    private List<SgfProperty> buildRootProperties(List<SgfNode> nodes) {
      List<SgfProperty> collected = new ArrayList<SgfProperty>();
      for (int i = 0; i < nodes.size(); i++) {
        SgfNode node = nodes.get(i);
        if (i > 0 && !extractMoves(node.properties).isEmpty()) {
          break;
        }
        collected.addAll(node.properties);
      }
      return buildCleanRootProperties(collected);
    }

    private List<SgfProperty> buildCleanRootProperties(List<SgfProperty> properties) {
      LinkedHashMap<String, SgfProperty> singleValueProperties =
          new LinkedHashMap<String, SgfProperty>();
      LinkedHashMap<String, List<String>> multiValueProperties =
          new LinkedHashMap<String, List<String>>();
      LinkedHashSet<String> encounterOrder = new LinkedHashSet<String>();

      for (SgfProperty property : properties) {
        if (!ROOT_PROPERTIES.contains(property.name) || property.values.isEmpty()) {
          continue;
        }
        encounterOrder.add(property.name);
        if (ROOT_MULTI_VALUE_PROPERTIES.contains(property.name)) {
          List<String> merged = multiValueProperties.get(property.name);
          if (merged == null) {
            merged = new ArrayList<String>();
            multiValueProperties.put(property.name, merged);
          }
          merged.addAll(property.values);
        } else {
          SgfProperty existing = singleValueProperties.get(property.name);
          if (existing == null || propertyHasContent(property)) {
            singleValueProperties.put(property.name, copyProperty(property));
          }
        }
      }

      ensureDefaultRootProperty(singleValueProperties, encounterOrder, "GM", "1");
      ensureDefaultRootProperty(singleValueProperties, encounterOrder, "FF", "4");
      ensureDefaultRootProperty(singleValueProperties, encounterOrder, "CA", "UTF-8");
      ensureDefaultRootProperty(singleValueProperties, encounterOrder, "SZ", "19");

      List<SgfProperty> ordered = new ArrayList<SgfProperty>();
      LinkedHashSet<String> appended = new LinkedHashSet<String>();
      for (String name : ROOT_PROPERTY_ORDER) {
        appendRootProperty(name, singleValueProperties, multiValueProperties, ordered, appended);
      }
      for (String name : encounterOrder) {
        appendRootProperty(name, singleValueProperties, multiValueProperties, ordered, appended);
      }
      return ordered;
    }

    private void ensureDefaultRootProperty(
        LinkedHashMap<String, SgfProperty> singleValueProperties,
        LinkedHashSet<String> encounterOrder,
        String name,
        String value) {
      if (!singleValueProperties.containsKey(name)) {
        encounterOrder.add(name);
        singleValueProperties.put(name, new SgfProperty(name, Arrays.asList(value)));
      }
    }

    private void appendRootProperty(
        String name,
        LinkedHashMap<String, SgfProperty> singleValueProperties,
        LinkedHashMap<String, List<String>> multiValueProperties,
        List<SgfProperty> output,
        LinkedHashSet<String> appended) {
      if (ROOT_MULTI_VALUE_PROPERTIES.contains(name)) {
        List<String> values = multiValueProperties.get(name);
        if (values != null && !values.isEmpty() && appended.add(name)) {
          output.add(new SgfProperty(name, new ArrayList<String>(values)));
        }
        return;
      }
      SgfProperty property = singleValueProperties.get(name);
      if (property != null && appended.add(name)) {
        output.add(property);
      }
    }

    private boolean propertyHasContent(SgfProperty property) {
      for (String value : property.values) {
        if (value != null && !value.trim().isEmpty()) {
          return true;
        }
      }
      return false;
    }

    private SgfProperty copyProperty(SgfProperty property) {
      return new SgfProperty(property.name, new ArrayList<String>(property.values));
    }

    private List<SgfMove> recoverPreferredMoves(SgfTree tree, List<SgfMove> rootMoves) {
      List<SgfMove> defaultMoves = extractMainLineMoves(tree, true);
      if (!looksLikeWindowedFox(tree)) {
        return defaultMoves;
      }
      List<SgfMove> mergedMoves = recoverMergedFoxBranch(tree);
      if (mergedMoves.size() >= Math.max(12, defaultMoves.size())) {
        return mergedMoves;
      }
      List<FoxWindow> windows = extractFoxWindows(tree.children);
      if (windows.size() < 5) {
        return defaultMoves;
      }
      List<SgfMove> recovered = recoverWindowedMoves(windows, rootMoves);
      if (recovered.size() >= Math.max(15, defaultMoves.size() + 4)) {
        return recovered;
      }
      return defaultMoves;
    }

    private boolean looksLikeWindowedFox(SgfTree tree) {
      if (tree.children.size() < 20) {
        return false;
      }
      int candidateChildren = 0;
      for (SgfTree child : tree.children) {
        int moveNodes = countNodesWithMoves(child.nodes);
        if (child.children.isEmpty() && moveNodes >= 7 && moveNodes <= 10) {
          candidateChildren++;
        }
      }
      return candidateChildren >= Math.max(10, tree.children.size() / 3);
    }

    private List<SgfMove> recoverMergedFoxBranch(SgfTree tree) {
      int originalWidth = Board.boardWidth;
      int originalHeight = Board.boardHeight;
      try {
        int[] boardSize =
            detectBoardSize(tree.nodes.isEmpty() ? null : tree.nodes.get(0).properties);
        Board.boardWidth = boardSize[0];
        Board.boardHeight = boardSize[1];

        BoardHistoryList history =
            new BoardHistoryList(BoardData.empty(boardSize[0], boardSize[1]));
        mergeTreeIntoHistory(tree, history, history.getCurrentHistoryNode());
        return extractMainBranch(history);
      } catch (RuntimeException e) {
        return Collections.emptyList();
      } finally {
        Board.boardWidth = originalWidth;
        Board.boardHeight = originalHeight;
      }
    }

    private int[] detectBoardSize(List<SgfProperty> properties) {
      int width = 19;
      int height = 19;
      if (properties == null) {
        return new int[] {width, height};
      }
      for (SgfProperty property : properties) {
        if (!"SZ".equals(property.name) || property.values.isEmpty()) {
          continue;
        }
        String sizeText = property.values.get(property.values.size() - 1);
        if (sizeText == null || sizeText.trim().isEmpty()) {
          continue;
        }
        String trimmed = sizeText.trim();
        if (trimmed.contains(":")) {
          String[] parts = trimmed.split(":", 2);
          if (parts.length == 2) {
            width = safeParseBoardSize(parts[0], 19);
            height = safeParseBoardSize(parts[1], width);
          }
        } else {
          width = safeParseBoardSize(trimmed, 19);
          height = width;
        }
      }
      return new int[] {Math.max(2, width), Math.max(2, height)};
    }

    private int safeParseBoardSize(String value, int defaultValue) {
      try {
        return Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }

    private void mergeTreeIntoHistory(
        SgfTree tree, BoardHistoryList history, BoardHistoryNode branchStart) {
      history.setHead(branchStart);
      for (SgfNode node : tree.nodes) {
        applyNodeToHistory(node, history);
      }
      BoardHistoryNode nextStart = history.getCurrentHistoryNode();
      for (SgfTree child : tree.children) {
        mergeTreeIntoHistory(child, history, nextStart);
      }
    }

    private void applyNodeToHistory(SgfNode node, BoardHistoryList history) {
      for (SgfProperty property : node.properties) {
        if (property.values == null || property.values.isEmpty()) {
          continue;
        }
        if ("AB".equals(property.name)
            || "AW".equals(property.name)
            || "AE".equals(property.name)) {
          Stone stone =
              "AB".equals(property.name)
                  ? Stone.BLACK
                  : ("AW".equals(property.name) ? Stone.WHITE : Stone.EMPTY);
          for (String value : property.values) {
            int[] coord = SGFParser.convertSgfPosToCoord(value);
            if (coord != null) {
              history.setStone(coord, stone);
            }
          }
          continue;
        }
        if (!"B".equals(property.name) && !"W".equals(property.name)) {
          continue;
        }
        Stone color = "B".equals(property.name) ? Stone.BLACK : Stone.WHITE;
        for (String value : property.values) {
          int[] coord = SGFParser.convertSgfPosToCoord(value);
          if (coord == null) {
            history.pass(color, false);
          } else {
            history.place(coord[0], coord[1], color, false);
          }
        }
      }
    }

    private List<SgfMove> extractMainBranch(BoardHistoryList history) {
      List<SgfMove> moves = new ArrayList<SgfMove>();
      BoardHistoryNode node = history.getStart();
      while (node.next().isPresent()) {
        node = node.next().get();
        BoardData data = node.getData();
        Stone color = data.lastMoveColor;
        if (!data.isHistoryActionNode() || color == null || color == Stone.EMPTY) {
          continue;
        }
        String coordinate =
            data.isPassNode()
                ? SGFParser.passPos()
                : SGFParser.asCoord(data.lastMove.get()).toLowerCase();
        moves.add(new SgfMove(color.isBlack() ? "B" : "W", coordinate));
      }
      return moves;
    }

    private int countNodesWithMoves(List<SgfNode> nodes) {
      int count = 0;
      for (SgfNode node : nodes) {
        if (!extractMoves(node.properties).isEmpty()) {
          count++;
        }
      }
      return count;
    }

    private List<FoxWindow> extractFoxWindows(List<SgfTree> children) {
      List<FoxWindow> windows = new ArrayList<FoxWindow>();
      for (int i = 0; i < children.size(); i++) {
        SgfTree child = children.get(i);
        List<SgfMove> context = new ArrayList<SgfMove>();
        List<SgfMove> sequence = new ArrayList<SgfMove>();
        boolean firstNode = true;
        for (SgfNode node : child.nodes) {
          List<SgfMove> moves = extractMoves(node.properties);
          if (moves.isEmpty()) {
            continue;
          }
          if (firstNode && moves.size() >= 2) {
            sequence.add(moves.get(0));
            context.add(moves.get(1));
          } else {
            sequence.add(moves.get(0));
          }
          firstNode = false;
        }
        if (!sequence.isEmpty()) {
          windows.add(new FoxWindow(i, context, sequence));
        }
      }
      return windows;
    }

    private List<SgfMove> recoverWindowedMoves(List<FoxWindow> windows, List<SgfMove> rootMoves) {
      List<FoxWindow> seeds = pickSeedWindows(windows, rootMoves);
      if (seeds.isEmpty()) {
        return Collections.emptyList();
      }
      List<List<SgfMove>> rootPrefixes = buildRootPrefixCandidates(rootMoves);
      List<SgfMove> bestMoves = Collections.emptyList();
      int bestScore = Integer.MIN_VALUE;
      int bestPriority = Integer.MIN_VALUE;
      for (List<SgfMove> rootPrefix : rootPrefixes) {
        for (FoxWindow seed : seeds) {
          int priority = seedPriority(seed, rootMoves);
          List<SgfMove> attempt = buildWindowedSequence(seed, windows, rootPrefix);
          if (attempt.isEmpty()) {
            continue;
          }
          int score = scoreRecoveredMoves(attempt, rootPrefix, rootMoves);
          if (score > bestScore
              || (score == bestScore && priority > bestPriority)
              || (score == bestScore
                  && priority == bestPriority
                  && attempt.size() > bestMoves.size())) {
            bestMoves = attempt;
            bestScore = score;
            bestPriority = priority;
          }
        }
      }
      return bestMoves;
    }

    private List<List<SgfMove>> buildRootPrefixCandidates(List<SgfMove> rootMoves) {
      List<List<SgfMove>> candidates = new ArrayList<List<SgfMove>>();
      addRootPrefixCandidate(candidates, rootMoves);
      if (!rootMoves.isEmpty()) {
        addRootPrefixCandidate(
            candidates, new ArrayList<SgfMove>(Arrays.asList(rootMoves.get(rootMoves.size() - 1))));
      }
      if (rootMoves.size() > 1) {
        List<SgfMove> reversed = new ArrayList<SgfMove>(rootMoves);
        Collections.reverse(reversed);
        addRootPrefixCandidate(candidates, reversed);
      }
      addRootPrefixCandidate(candidates, Collections.<SgfMove>emptyList());
      if (candidates.isEmpty()) {
        candidates.add(Collections.<SgfMove>emptyList());
      }
      return candidates;
    }

    private void addRootPrefixCandidate(List<List<SgfMove>> candidates, List<SgfMove> prefix) {
      List<SgfMove> normalized =
          prefix == null ? Collections.<SgfMove>emptyList() : new ArrayList<SgfMove>(prefix);
      if (!normalized.isEmpty() && !isAlternating(normalized)) {
        return;
      }
      for (List<SgfMove> existing : candidates) {
        if (existing.equals(normalized)) {
          return;
        }
      }
      candidates.add(normalized);
    }

    private List<FoxWindow> pickSeedWindows(List<FoxWindow> windows, List<SgfMove> rootMoves) {
      List<FoxWindow> ordered = new ArrayList<FoxWindow>(windows);
      Collections.sort(
          ordered,
          (left, right) -> {
            int leftPriority = seedPriority(left, rootMoves);
            int rightPriority = seedPriority(right, rootMoves);
            if (leftPriority != rightPriority) {
              return rightPriority - leftPriority;
            }
            return left.index - right.index;
          });
      if (ordered.size() > 16) {
        return new ArrayList<FoxWindow>(ordered.subList(0, 16));
      }
      return ordered;
    }

    private int seedPriority(FoxWindow window, List<SgfMove> rootMoves) {
      String lastRootColor = rootMoves.isEmpty() ? null : rootMoves.get(rootMoves.size() - 1).color;
      SgfMove lastRootMove = rootMoves.isEmpty() ? null : rootMoves.get(rootMoves.size() - 1);
      if (!window.context.isEmpty()
          && lastRootMove != null
          && lastRootMove.equals(window.context.get(0))) {
        return 4;
      }
      if (window.context.isEmpty()
          && !window.sequence.isEmpty()
          && lastRootColor != null
          && !lastRootColor.equals(window.sequence.get(0).color)) {
        return 3;
      }
      if (!window.sequence.isEmpty()
          && lastRootColor != null
          && !lastRootColor.equals(window.sequence.get(0).color)) {
        return 2;
      }
      if (window.context.isEmpty()) {
        return 1;
      }
      return 0;
    }

    private List<SgfMove> buildWindowedSequence(
        FoxWindow seed, List<FoxWindow> windows, List<SgfMove> rootPrefix) {
      List<SgfMove> current = new ArrayList<SgfMove>(rootPrefix);
      int seedOverlap = rootPrefix.isEmpty() ? 0 : computeOverlap(rootPrefix, seed.sequence);
      if (!rootPrefix.isEmpty() && !canAppend(rootPrefix, seed.sequence, seedOverlap)) {
        return Collections.emptyList();
      }
      appendMoves(current, seed.sequence.subList(seedOverlap, seed.sequence.size()));
      if (!isAlternating(current)) {
        return Collections.emptyList();
      }
      LinkedHashSet<Integer> used = new LinkedHashSet<Integer>();
      used.add(seed.index);
      while (current.size() < 600) {
        FoxCandidate best = null;
        for (FoxWindow window : windows) {
          if (used.contains(window.index)) {
            continue;
          }
          best =
              selectBetterCandidate(
                  best, buildCandidate(current, window.sequence, window.index, false, 3));
          if (!window.skipSequence.isEmpty()) {
            best =
                selectBetterCandidate(
                    best, buildCandidate(current, window.skipSequence, window.index, false, 2));
          }
          if (!window.context.isEmpty()) {
            best =
                selectBetterCandidate(
                    best, buildCandidate(current, window.augmented, window.index, true, 1));
          }
        }
        if (best == null || best.overlap < 2) {
          break;
        }
        appendMoves(current, best.target.subList(best.overlap, best.target.size()));
        used.add(best.index);
      }
      if (current.size() <= rootPrefix.size()) {
        return Collections.emptyList();
      }
      return new ArrayList<SgfMove>(current.subList(rootPrefix.size(), current.size()));
    }

    private int scoreRecoveredMoves(
        List<SgfMove> recoveredMoves, List<SgfMove> rootPrefix, List<SgfMove> rootMoves) {
      int score = recoveredMoves.size() * 100;
      score += rootPrefix.size() * 8;
      if (!rootPrefix.isEmpty() && recoveredMoves.size() > rootPrefix.size()) {
        SgfMove firstRecovered = recoveredMoves.get(0);
        if (firstRecovered != null
            && rootPrefix.get(rootPrefix.size() - 1) != null
            && !firstRecovered.color.equals(rootPrefix.get(rootPrefix.size() - 1).color)) {
          score += 24;
        }
      }
      score -= repeatPenalty(recoveredMoves);
      if (!rootMoves.isEmpty() && recoveredMoves.size() >= rootMoves.size()) {
        boolean matchesRoot = true;
        for (int i = 0; i < rootMoves.size(); i++) {
          if (!rootMoves.get(i).equals(recoveredMoves.get(i))) {
            matchesRoot = false;
            break;
          }
        }
        if (matchesRoot) {
          score += 20;
        }
      }
      return score;
    }

    private int repeatPenalty(List<SgfMove> moves) {
      int penalty = 0;
      for (int i = 0; i < moves.size(); i++) {
        for (int j = Math.max(0, i - 12); j < i; j++) {
          if (moves.get(i).equals(moves.get(j))) {
            penalty += Math.max(12, 120 - (i - j) * 6);
          }
        }
      }
      for (int blockSize = 2; blockSize <= 4; blockSize++) {
        penalty += repeatedBlockPenalty(moves, blockSize);
      }
      return penalty;
    }

    private int repeatedBlockPenalty(List<SgfMove> moves, int blockSize) {
      if (moves.size() < blockSize * 2) {
        return 0;
      }
      int penalty = 0;
      LinkedHashMap<String, Integer> firstSeen = new LinkedHashMap<String, Integer>();
      for (int i = 0; i + blockSize <= moves.size(); i++) {
        String key = movesBlockKey(moves, i, blockSize);
        Integer previousIndex = firstSeen.get(key);
        if (previousIndex == null) {
          firstSeen.put(key, i);
          continue;
        }
        if (i - previousIndex <= 16) {
          penalty += blockSize * 180;
        }
      }
      return penalty;
    }

    private String movesBlockKey(List<SgfMove> moves, int start, int blockSize) {
      StringBuilder key = new StringBuilder(blockSize * 8);
      for (int i = 0; i < blockSize; i++) {
        SgfMove move = moves.get(start + i);
        key.append(move.color).append(':').append(move.coordinate).append('|');
      }
      return key.toString();
    }

    private FoxCandidate buildCandidate(
        List<SgfMove> current,
        List<SgfMove> target,
        int index,
        boolean usesContext,
        int variantRank) {
      if (target.isEmpty()) {
        return null;
      }
      int overlap = computeOverlap(current, target);
      if (overlap <= 0) {
        return null;
      }
      if (!canAppend(current, target, overlap)) {
        return null;
      }
      return new FoxCandidate(
          index, target, overlap, usesContext, target.size() - overlap, variantRank);
    }

    private FoxCandidate selectBetterCandidate(FoxCandidate current, FoxCandidate next) {
      if (next == null) {
        return current;
      }
      if (current == null) {
        return next;
      }
      if (next.overlap != current.overlap) {
        return next.overlap > current.overlap ? next : current;
      }
      if (next.appendSize != current.appendSize) {
        return next.appendSize > current.appendSize ? next : current;
      }
      if (next.variantRank != current.variantRank) {
        return next.variantRank > current.variantRank ? next : current;
      }
      if (next.usesContext != current.usesContext) {
        return current.usesContext ? current : next;
      }
      return next.index < current.index ? next : current;
    }

    private int computeOverlap(List<SgfMove> current, List<SgfMove> target) {
      int best = 0;
      int max = Math.min(current.size(), target.size());
      for (int size = 1; size <= max; size++) {
        boolean matches = true;
        for (int i = 0; i < size; i++) {
          if (!current.get(current.size() - size + i).equals(target.get(i))) {
            matches = false;
            break;
          }
        }
        if (matches) {
          best = size;
        }
      }
      return best;
    }

    private boolean canAppend(List<SgfMove> current, List<SgfMove> target, int overlap) {
      if (overlap >= target.size()) {
        return false;
      }
      List<SgfMove> appended = target.subList(overlap, target.size());
      if (appended.isEmpty()) {
        return false;
      }
      if (!current.isEmpty()
          && current.get(current.size() - 1).color.equals(appended.get(0).color)) {
        return false;
      }
      return isAlternating(appended);
    }

    private void appendMoves(List<SgfMove> current, List<SgfMove> source) {
      current.addAll(source);
    }

    private List<SgfMove> extractMainLineMoves(SgfTree tree, boolean skipRootNode) {
      List<SgfMove> moves = new ArrayList<SgfMove>();
      int startNode = skipRootNode ? 1 : 0;
      for (int i = startNode; i < tree.nodes.size(); i++) {
        moves.addAll(extractMoves(tree.nodes.get(i).properties));
      }
      SgfTree child = selectPreferredChild(tree.children);
      if (child != null) {
        moves.addAll(extractMainLineMoves(child, false));
      }
      return moves;
    }

    private SgfTree selectPreferredChild(List<SgfTree> children) {
      for (SgfTree child : children) {
        if (hasMoves(child, false)) {
          return child;
        }
      }
      return null;
    }

    private boolean hasMoves(SgfTree tree, boolean skipRootNode) {
      int startNode = skipRootNode ? 1 : 0;
      for (int i = startNode; i < tree.nodes.size(); i++) {
        if (!extractMoves(tree.nodes.get(i).properties).isEmpty()) {
          return true;
        }
      }
      for (SgfTree child : tree.children) {
        if (hasMoves(child, false)) {
          return true;
        }
      }
      return false;
    }

    private List<SgfMove> extractMoves(List<SgfProperty> properties) {
      List<SgfMove> moves = new ArrayList<SgfMove>();
      for (SgfProperty property : properties) {
        if (!"B".equals(property.name) && !"W".equals(property.name)) {
          continue;
        }
        for (String value : property.values) {
          moves.add(new SgfMove(property.name, value));
        }
      }
      return moves;
    }

    private List<SgfMove> chooseCompatibleRootPrefix(List<SgfMove> rootMoves, String nextColor) {
      List<SgfMove> best = new ArrayList<SgfMove>();
      for (int i = 1; i <= rootMoves.size(); i++) {
        List<SgfMove> prefix = rootMoves.subList(0, i);
        if (!isAlternating(prefix)) {
          continue;
        }
        if (nextColor != null && nextColor.equals(prefix.get(prefix.size() - 1).color)) {
          continue;
        }
        if (prefix.size() > best.size()) {
          best = new ArrayList<SgfMove>(prefix);
        }
      }
      if (!best.isEmpty()) {
        return best;
      }
      for (int i = 1; i <= rootMoves.size(); i++) {
        List<SgfMove> prefix = rootMoves.subList(0, i);
        if (isAlternating(prefix) && prefix.size() > best.size()) {
          best = new ArrayList<SgfMove>(prefix);
        }
      }
      return best;
    }

    private boolean isAlternating(List<SgfMove> moves) {
      for (int i = 1; i < moves.size(); i++) {
        if (moves.get(i - 1).color.equals(moves.get(i).color)) {
          return false;
        }
      }
      return true;
    }

    private List<SgfMove> normalizeMoves(List<SgfMove> rootMoves, List<SgfMove> mainLineMoves) {
      List<SgfMove> normalized = new ArrayList<SgfMove>();
      String lastColor = null;
      normalized = appendNormalizedMoves(normalized, rootMoves, lastColor);
      if (!normalized.isEmpty()) {
        lastColor = normalized.get(normalized.size() - 1).color;
      }
      return appendNormalizedMoves(normalized, mainLineMoves, lastColor);
    }

    private List<SgfMove> appendNormalizedMoves(
        List<SgfMove> target, List<SgfMove> source, String lastColor) {
      String currentLastColor = lastColor;
      for (SgfMove move : source) {
        if (move == null || move.color == null) {
          continue;
        }
        if (currentLastColor == null || !currentLastColor.equals(move.color)) {
          target.add(move);
          currentLastColor = move.color;
        }
      }
      return target;
    }

    private void appendProperties(StringBuilder out, List<SgfProperty> properties) {
      for (SgfProperty property : properties) {
        out.append(property.name);
        for (String value : property.values) {
          out.append('[').append(value).append(']');
        }
      }
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

  private static final class SgfTree {
    private final List<SgfNode> nodes;
    private final List<SgfTree> children;

    private SgfTree(List<SgfNode> nodes, List<SgfTree> children) {
      this.nodes = nodes;
      this.children = children;
    }
  }

  private static final class SgfNode {
    private final List<SgfProperty> properties;

    private SgfNode(List<SgfProperty> properties) {
      this.properties = properties;
    }
  }

  private static final class SgfProperty {
    private final String name;
    private final List<String> values;

    private SgfProperty(String name, List<String> values) {
      this.name = name;
      this.values = values;
    }
  }

  private static final class FoxWindow {
    private final int index;
    private final List<SgfMove> context;
    private final List<SgfMove> sequence;
    private final List<SgfMove> skipSequence;
    private final List<SgfMove> augmented;

    private FoxWindow(int index, List<SgfMove> context, List<SgfMove> sequence) {
      this.index = index;
      this.context = new ArrayList<SgfMove>(context);
      this.sequence = new ArrayList<SgfMove>(sequence);
      this.skipSequence =
          sequence.size() > 1
              ? new ArrayList<SgfMove>(sequence.subList(1, sequence.size()))
              : Collections.<SgfMove>emptyList();
      this.augmented = new ArrayList<SgfMove>(context.size() + sequence.size());
      this.augmented.addAll(context);
      this.augmented.addAll(sequence);
    }
  }

  private static final class FoxCandidate {
    private final int index;
    private final List<SgfMove> target;
    private final int overlap;
    private final boolean usesContext;
    private final int appendSize;
    private final int variantRank;

    private FoxCandidate(
        int index,
        List<SgfMove> target,
        int overlap,
        boolean usesContext,
        int appendSize,
        int variantRank) {
      this.index = index;
      this.target = target;
      this.overlap = overlap;
      this.usesContext = usesContext;
      this.appendSize = appendSize;
      this.variantRank = variantRank;
    }
  }

  private static final class SgfMove {
    private final String color;
    private final String coordinate;

    private SgfMove(String color, String coordinate) {
      this.color = color;
      this.coordinate = coordinate;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof SgfMove)) {
        return false;
      }
      SgfMove move = (SgfMove) other;
      return safeEquals(color, move.color) && safeEquals(coordinate, move.coordinate);
    }

    @Override
    public int hashCode() {
      int result = color == null ? 0 : color.hashCode();
      result = 31 * result + (coordinate == null ? 0 : coordinate.hashCode());
      return result;
    }

    private boolean safeEquals(String left, String right) {
      return left == null ? right == null : left.equals(right);
    }
  }
}
