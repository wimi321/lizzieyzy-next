package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.FoxKifuDownload;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
      List<SgfProperty> rootProperties = buildCleanRootProperties(rootNode.properties);
      List<SgfMove> rootMoves = extractMoves(rootNode.properties);
      List<SgfMove> mainLineMoves = extractMainLineMoves(tree, true);
      String nextColor = mainLineMoves.isEmpty() ? null : mainLineMoves.get(0).color;
      List<SgfMove> rootPrefix = chooseCompatibleRootPrefix(rootMoves, nextColor);
      List<SgfMove> moves = normalizeMoves(rootPrefix, mainLineMoves);

      if (rootProperties.isEmpty() && moves.isEmpty()) {
        return input;
      }

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

  private static final class SgfMove {
    private final String color;
    private final String coordinate;

    private SgfMove(String color, String coordinate) {
      this.color = color;
      this.coordinate = coordinate;
    }
  }
}
