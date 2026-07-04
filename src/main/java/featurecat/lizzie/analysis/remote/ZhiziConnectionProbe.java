package featurecat.lizzie.analysis.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ZhiziConnectionProbe {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);
  private static final int BOARD_SIZE = 19;
  private static final String RULES = "chinese";
  private static final String ANALYZE_PLAYER = "B";
  private static final int ANALYZE_INTERVAL = 50;
  private static final Set<String> SCALAR_KEYS =
      Set.of(
          "move",
          "visits",
          "edgeVisits",
          "utility",
          "winrate",
          "scoreMean",
          "scoreStdev",
          "scoreLead",
          "scoreSelfplay",
          "prior",
          "lcb",
          "utilityLcb",
          "weight",
          "order",
          "noResultValue",
          "isSymmetryOf");

  private ZhiziConnectionProbe() {}

  public static Result run(String accountToken, String args) throws IOException {
    return run(accountToken, args, DEFAULT_TIMEOUT);
  }

  static Result run(String accountToken, String args, Duration timeout) throws IOException {
    long started = System.nanoTime();
    ZhiziGtpTransport transport =
        new ZhiziGtpTransport(new ZhiziApiClient(), accountToken, args);
    try {
      transport.start();
      long readyAt = System.nanoTime();
      OutputStream stdin = transport.stdin();
      send(stdin, "boardsize " + BOARD_SIZE);
      send(stdin, "komi 7.5");
      send(stdin, "kata-set-rules " + RULES);
      send(stdin, "clear_board");
      send(stdin, "kata-analyze " + ANALYZE_PLAYER + " " + ANALYZE_INTERVAL);

      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        String line = readLineUntil(transport.stdout(), deadline);
        if (line == null) {
          break;
        }
        if (line.startsWith("info ")) {
          List<Candidate> candidates = parseInfoLine(line);
          if (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingInt(candidate -> candidate.order));
            sendQuietly(stdin, "stop");
            return new Result(
                millisBetween(started, readyAt),
                millisBetween(readyAt, System.nanoTime()),
                candidates.get(0),
                line);
          }
        }
      }
      throw new IOException("智子云算力已连接，但在限定时间内没有返回分析结果。");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("智子云算力连接测试被中断。", e);
    } finally {
      transport.close();
    }
  }

  static List<Candidate> parseInfoLine(String line) {
    List<Candidate> candidates = new ArrayList<>();
    if (line == null || line.isBlank()) {
      return candidates;
    }
    String[] blocks = line.trim().split("(?=\\binfo\\s)");
    for (String block : blocks) {
      String trimmed = block.trim();
      if (!trimmed.startsWith("info ")) {
        continue;
      }
      Candidate candidate = parseInfoBlock(trimmed.substring("info ".length()));
      if (!candidate.move.isEmpty()) {
        candidates.add(candidate);
      }
    }
    return candidates;
  }

  private static Candidate parseInfoBlock(String block) {
    String[] tokens = block.trim().split("\\s+");
    String move = "";
    int visits = -1;
    int order = Integer.MAX_VALUE;
    double winrate = Double.NaN;
    double scoreLead = Double.NaN;
    for (int i = 0; i < tokens.length; ) {
      String key = tokens[i];
      if ("pv".equals(key)) {
        break;
      }
      if (!SCALAR_KEYS.contains(key) || i + 1 >= tokens.length) {
        break;
      }
      String value = tokens[i + 1];
      switch (key) {
        case "move":
          move = value;
          break;
        case "visits":
          visits = parseInt(value, -1);
          break;
        case "order":
          order = parseInt(value, Integer.MAX_VALUE);
          break;
        case "winrate":
          winrate = parseDouble(value);
          break;
        case "scoreLead":
          scoreLead = parseDouble(value);
          break;
        default:
          break;
      }
      i += 2;
    }
    return new Candidate(move, visits, order, winrate, scoreLead);
  }

  private static String readLineUntil(InputStream stdout, long deadlineNanos)
      throws IOException, InterruptedException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    while (System.nanoTime() < deadlineNanos) {
      int available = stdout.available();
      if (available <= 0) {
        Thread.sleep(40);
        continue;
      }
      int ch = stdout.read();
      if (ch < 0) {
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
      }
      if (ch == '\n') {
        return line.toString(StandardCharsets.UTF_8).trim();
      }
      line.write(ch);
    }
    return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8).trim();
  }

  private static void send(OutputStream stdin, String command) throws IOException {
    stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
  }

  private static void sendQuietly(OutputStream stdin, String command) {
    try {
      send(stdin, command);
    } catch (IOException ignored) {
    }
  }

  private static long millisBetween(long startNanos, long endNanos) {
    return Math.max(0L, (endNanos - startNanos) / 1_000_000L);
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double parseDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  public static final class Result {
    public final long readyMillis;
    public final long firstInfoMillis;
    public final Candidate bestMove;
    public final String rawInfoLine;

    Result(long readyMillis, long firstInfoMillis, Candidate bestMove, String rawInfoLine) {
      this.readyMillis = readyMillis;
      this.firstInfoMillis = firstInfoMillis;
      this.bestMove = bestMove;
      this.rawInfoLine = rawInfoLine;
    }

    public String displaySummary() {
      String winrateText =
          Double.isNaN(bestMove.winrate)
              ? "胜率未知"
              : String.format(Locale.US, "胜率 %.1f%%", bestMove.winrate * 100.0);
      String visitsText = bestMove.visits >= 0 ? "，计算量 " + bestMove.visits : "";
      return "连接测试通过：已收到分析结果，首选 " + bestMove.move + "，" + winrateText + visitsText + "。";
    }
  }

  public static final class Candidate {
    public final String move;
    public final int visits;
    public final int order;
    public final double winrate;
    public final double scoreLead;

    Candidate(String move, int visits, int order, double winrate, double scoreLead) {
      this.move = move == null ? "" : move;
      this.visits = visits;
      this.order = order;
      this.winrate = winrate;
      this.scoreLead = scoreLead;
    }
  }
}
