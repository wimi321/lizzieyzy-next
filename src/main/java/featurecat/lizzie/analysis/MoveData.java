package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holds the data from Leelaz's pondering mode */
public class MoveData {
  public String coordinate;
  public int playouts;
  public double winrate;
  public List<String> variation;
  // 待完成
  public List<String> pvVisits;
  public double lcb;
  // public double oriwinrate;
  public double policy;
  // public int equalplayouts;
  public double scoreMean;
  public double scoreStdev;
  public boolean isKataData;
  public boolean isSaiData;
  public int order;
  public boolean isNextMove;
  public double bestWinrate;
  public double bestScoreMean;
  public boolean lastTimeUnlimited;
  public long lastTimeUnlimitedTime;
  public boolean isSymmetry = false;
  public List<Double> movesEstimateArray;

  public MoveData() {}

  /**
   * Parses a leelaz ponder output line. For example:
   *
   * <p>0.16 0.15
   *
   * <p>info move R5 visits 38 winrate 5404 order 0 pv R5 Q5 R6 S4 Q10 C3 D3 C4 C6 C5 D5
   *
   * <p>0.17
   *
   * <p>info move Q16 visits 80 winrate 4405 prior 1828 lcb 4379 order 0 pv Q16 D4
   *
   * @param line line of ponder output
   */
  public static MoveData fromInfo(String line) throws ArrayIndexOutOfBoundsException {
    MoveData result = new MoveData();
    String[] data = line.trim().split(" ");
    // int k =
    // Lizzie.config.config.getJSONObject("leelaz").getInt("max-suggestion-moves");
    //    boolean islcb =
    //        (Lizzie.config.leelaversion >= 17 && Lizzie.config.showlcbwinrate &&
    // !Lizzie.leelaz.noLcb);
    // Todo: Proper tag parsing in case gtp protocol is extended(?)/changed
    for (int i = 0; i < data.length - 1; i++) {
      String key = data[i];
      if (key.equals("pv")) {
        // Read variation to the end of line
        result.variation =
            new ArrayList<>(
                Arrays.asList(data)
                    .subList(
                        i + 1,
                        (Lizzie.config.limitBranchLength > 0
                                && data.length - i - 1 > Lizzie.config.limitBranchLength)
                            ? i + 1 + Lizzie.config.limitBranchLength
                            : data.length));
        // result.variation = result.variation.subList(i + 1, data.length);
        break;
      } else {
        String value = data[++i];
        if (key.equals("order")) {
          result.order = Integer.parseInt(value);
        }
        if (key.equals("move")) {
          result.coordinate = value;
        }
        if (key.equals("visits")) {
          result.playouts = Integer.parseInt(value);
        }
        if (key.equals("lcb")) {
          // LCB support
          result.lcb = Integer.parseInt(value) / 100.0;
          //          if (islcb) {
          //            result.winrate = Integer.parseInt(value) / 100.0;
          //          }
        }
        if (key.equals("prior")) {
          result.policy = Integer.parseInt(value) / 100.0;
        }

        if (key.equals("winrate")) {
          // support 0.16 0.15
          result.winrate = Integer.parseInt(value) / 100.0;
          // result.oriwinrate = result.winrate;
          //          if (!islcb) {
          //            result.winrate = Integer.parseInt(value) / 100.0;
          //          }
        }
      }
    }
    result.isKataData = false;
    result.isSaiData = false;
    return result;
  }

  //  Best:J3...   Nodes collected {8}: 800055 -> 569197 (0.00752s)
  //  public static MoveData fromInfoSpec(String line) throws ArrayIndexOutOfBoundsException {
  //    MoveData result = new MoveData();
  //    String[] data = line.trim().split("Best:");
  //    String[] data2 = data[1].split("\\.\\.\\.");
  //    result.coordinate = data2[0];
  //    result.winrate = 50.0;
  //    result.oriwinrate = 50.0;
  //    result.playouts = -999;
  //
  //    return result;
  //  }

  public static MoveData fromInfoSai(String line, boolean isSayuri)
      throws ArrayIndexOutOfBoundsException {
    MoveData result = new MoveData();
    String[] data = line.trim().split(" ");
    // int k =
    // Lizzie.config.config.getJSONObject("leelaz").getInt("max-suggestion-moves");
    //    boolean islcb =
    //        (Lizzie.config.leelaversion >= 17 && Lizzie.config.showlcbwinrate &&
    // !Lizzie.leelaz.noLcb);
    // Todo: Proper tag parsing in case gtp protocol is extended(?)/changed
    for (int i = 0; i < data.length - 1; i++) {
      String key = data[i];
      if (key.equals("pv")) {
        // Read variation to the end of line
        result.variation =
            new ArrayList<>(
                Arrays.asList(data)
                    .subList(
                        i + 1,
                        (Lizzie.config.limitBranchLength > 0
                                && data.length - i - 1 > Lizzie.config.limitBranchLength)
                            ? i + 1 + Lizzie.config.limitBranchLength
                            : data.length));
        // result.variation = result.variation.subList(i + 1, data.length);
        break;
      } else {
        String value = data[++i];
        if (key.equals("order")) {
          result.order = Integer.parseInt(value);
        }
        if (key.equals("move")) {
          result.coordinate = value;
        }
        if (key.equals("visits")) {
          result.playouts = Integer.parseInt(value);
        }
        if (key.equals("lcb")) {
          // LCB support
          result.lcb = Integer.parseInt(value) / 100.0;
          //          if (islcb) {
          //            result.winrate = Integer.parseInt(value) / 100.0;
          //          }
        }
        if (key.equals("prior")) {
          result.policy = Integer.parseInt(value) / 100.0;
        }
        if (isSayuri) {
          if (key.equals("scoreLead")) {
            result.scoreMean = Double.parseDouble(value);
          }
        } else if (key.equals("areas")) {
          result.scoreMean =
              Lizzie.board.getHistory().isBlacksTurn()
                  ? result.scoreMean = Integer.parseInt(value) / 10000.0
                  : -Integer.parseInt(value) / 10000.0;
        }
        if (key.equals("winrate")) {
          // support 0.16 0.15
          result.winrate = Integer.parseInt(value) / 100.0;
          // result.oriwinrate = result.winrate;
          //          if (!islcb) {
          //            result.winrate = Integer.parseInt(value) / 100.0;
          //          }
        }
      }
    }
    result.isKataData = true;
    result.isSaiData = true;
    return result;
  }

  public static MoveData fromInfoKatago(String line) throws ArrayIndexOutOfBoundsException {
    MoveData result = new MoveData();
    TokenCursor tokens = new TokenCursor(line);
    while (tokens.next()) {
      if (tokens.matches("pv")) {
        parseKataGoVariation(tokens, result);
        break;
      }
      int keyStart = tokens.start;
      int keyEnd = tokens.end;
      if (!tokens.next()) {
        break;
      }
      if (tokens.matches(line, keyStart, keyEnd, "order")) {
        result.order = tokens.fastInt();
      } else if (tokens.matches(line, keyStart, keyEnd, "move")) {
        result.coordinate = tokens.text();
      } else if (tokens.matches(line, keyStart, keyEnd, "visits")) {
        result.playouts = tokens.fastInt();
      } else if (tokens.matches(line, keyStart, keyEnd, "lcb")) {
        result.lcb = tokens.fastDouble() * 100;
      } else if (tokens.matches(line, keyStart, keyEnd, "prior")) {
        result.policy = tokens.fastDouble() * 100;
      } else if (tokens.matches(line, keyStart, keyEnd, "winrate")) {
        result.winrate = tokens.fastDouble() * 100;
      } else if (tokens.matches(line, keyStart, keyEnd, "scoreLead")
          || tokens.matches(line, keyStart, keyEnd, "scoreMean")) {
        result.scoreMean = tokens.fastDouble();
      } else if (tokens.matches(line, keyStart, keyEnd, "scoreStdev")) {
        result.scoreStdev = tokens.fastDouble();
      } else if (tokens.matches(line, keyStart, keyEnd, "isSymmetryOf")) {
        result.isSymmetry = true;
      }
    }
    result.isKataData = true;
    result.isSaiData = false;
    return result;
  }

  private static void parseKataGoVariation(TokenCursor tokens, MoveData result) {
    int branchLimit = Lizzie.config == null ? 0 : Lizzie.config.limitBranchLength;
    result.variation = new ArrayList<>();
    int mode = 0;
    double[] ownership = null;
    int ownershipSize = 0;
    while (tokens.next()) {
      if (tokens.matches("pvVisits")) {
        mode = 1;
        result.pvVisits = new ArrayList<>();
        continue;
      }
      if (tokens.matches("movesOwnership")) {
        mode = 2;
        ownership = new double[Math.max(1, tokens.remainingTokenCount())];
        continue;
      }
      if (mode == 0) {
        if (branchLimit <= 0 || result.variation.size() < branchLimit) {
          result.variation.add(tokens.text());
        }
      } else if (mode == 1) {
        if (result.pvVisits.size() < result.variation.size()) {
          result.pvVisits.add(tokens.text());
        }
      } else {
        if (ownershipSize == ownership.length) {
          ownership = Arrays.copyOf(ownership, ownership.length * 2);
        }
        try {
          double ownershipValue = tokens.fastDouble();
          ownership[ownershipSize++] = ownershipValue;
        } catch (NumberFormatException ignored) {
          break;
        }
      }
    }
    if (ownership != null) {
      result.movesEstimateArray = BoardData.compactEstimateArray(ownership, ownershipSize);
    }
  }

  private static final class TokenCursor {
    private final String value;
    private int position;
    private int start;
    private int end;

    private TokenCursor(String value) {
      this.value = value == null ? "" : value;
    }

    private boolean next() {
      int length = value.length();
      while (position < length && value.charAt(position) <= ' ') {
        position++;
      }
      if (position >= length) {
        return false;
      }
      start = position;
      while (position < length && value.charAt(position) > ' ') {
        position++;
      }
      end = position;
      return true;
    }

    private boolean matches(String expected) {
      return matches(value, start, end, expected);
    }

    private boolean matches(String source, int tokenStart, int tokenEnd, String expected) {
      return tokenEnd - tokenStart == expected.length()
          && source.regionMatches(tokenStart, expected, 0, expected.length());
    }

    private String text() {
      return value.substring(start, end);
    }

    private int remainingTokenCount() {
      int count = 0;
      int index = position;
      while (index < value.length()) {
        while (index < value.length() && value.charAt(index) <= ' ') {
          index++;
        }
        if (index >= value.length()) {
          break;
        }
        count++;
        while (index < value.length() && value.charAt(index) > ' ') {
          index++;
        }
      }
      return count;
    }

    private int fastInt() {
      int index = start;
      boolean negative = false;
      if (index < end && (value.charAt(index) == '-' || value.charAt(index) == '+')) {
        negative = value.charAt(index++) == '-';
      }
      if (index >= end) {
        return Integer.parseInt(text());
      }
      long result = 0L;
      long limit = negative ? 2_147_483_648L : Integer.MAX_VALUE;
      while (index < end) {
        char current = value.charAt(index++);
        if (current < '0' || current > '9') {
          return Integer.parseInt(text());
        }
        int digit = current - '0';
        if (result > (limit - digit) / 10L) {
          return Integer.parseInt(text());
        }
        result = result * 10L + digit;
      }
      return negative ? (int) -result : (int) result;
    }

    private double fastDouble() {
      int index = start;
      boolean negative = false;
      if (index < end && (value.charAt(index) == '-' || value.charAt(index) == '+')) {
        negative = value.charAt(index++) == '-';
      }
      double result = 0.0;
      boolean digit = false;
      while (index < end) {
        char current = value.charAt(index);
        if (current < '0' || current > '9') {
          break;
        }
        digit = true;
        result = result * 10.0 + current - '0';
        index++;
      }
      if (index < end && value.charAt(index) == '.') {
        index++;
        double place = 0.1;
        while (index < end) {
          char current = value.charAt(index);
          if (current < '0' || current > '9') {
            break;
          }
          digit = true;
          result += (current - '0') * place;
          place *= 0.1;
          index++;
        }
      }
      if (!digit) {
        return Double.parseDouble(text());
      }
      if (index < end && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
        index++;
        boolean exponentNegative = false;
        if (index < end && (value.charAt(index) == '-' || value.charAt(index) == '+')) {
          exponentNegative = value.charAt(index++) == '-';
        }
        int exponent = 0;
        boolean exponentDigit = false;
        while (index < end) {
          char current = value.charAt(index);
          if (current < '0' || current > '9') {
            break;
          }
          exponentDigit = true;
          if (exponent < 10_000) {
            exponent = Math.min(10_000, exponent * 10 + current - '0');
          }
          index++;
        }
        if (!exponentDigit || index != end) {
          return Double.parseDouble(text());
        }
        result *= Math.pow(10.0, exponentNegative ? -exponent : exponent);
      } else if (index != end) {
        return Double.parseDouble(text());
      }
      return negative ? -result : result;
    }
  }

  public static MoveData fromInfofromfile(String line, List<MoveData> bestMoves)
      throws ArrayIndexOutOfBoundsException {
    MoveData result = new MoveData();
    String[] data = line.trim().split(" ");

    // Todo: Proper tag parsing in case gtp protocol is extended(?)/changed
    for (int i = 0; i < data.length - 1; i++) {
      String key = data[i];
      //      if (key.equals("pv")) {
      //        // Read variation to the end of line
      //        result.variation = new ArrayList<>(Arrays.asList(data));
      //        result.variation = result.variation.subList(i + 1, data.length);
      //        break;
      //      }
      if (key.equals("pv")) {
        int pvVisitsPos = -1;
        for (int s = i + 1; s < data.length; s++) {
          String subKey = data[s];
          if (subKey.equals("pvVisits")) {
            pvVisitsPos = s;
            result.pvVisits =
                new ArrayList<>(Arrays.asList(data).subList(s + 1, data.length));
            break;
          }
        }
        // Read variation to the end of line
        int length = pvVisitsPos > -1 ? pvVisitsPos : data.length;
        result.variation =
            new ArrayList<>(
                Arrays.asList(data)
                    .subList(
                        i + 1,
                        (Lizzie.config.limitBranchLength > 0
                                && length - i - 1 > Lizzie.config.limitBranchLength)
                            ? i + 1 + Lizzie.config.limitBranchLength
                            : length));
        // result.variation = result.variation.subList(i + 1, data.length);
        break;
      } else {
        String value = data[++i];
        if (key.equals("move")) {
          result.coordinate = value;
        }
        if (key.equals("visits")) {
          result.playouts = Integer.parseInt(value);
        }
        if (key.equals("winrate")) {
          // support 0.16 0.15
          result.winrate = Integer.parseInt(value) / 100.0;
          // result.oriwinrate = result.winrate;
          result.lcb = result.winrate;
        }
        if (key.equals("prior")) {
          try {
            result.policy = Integer.parseInt(value) / 100.0;
          } catch (NumberFormatException err) {
            result.policy = Double.parseDouble(value);
          }
        }
        if (key.equals("scoreMean")) {
          // support 0.16 0.15
          result.scoreMean = Double.parseDouble(value);
          Lizzie.board.isKataBoard = true;
          result.isKataData = true;
        }
      }
    }
    result.order = bestMoves.size();
    return result;
  }

  public static MoveData fromSummaryKata(String summary) {
    if (summary.contains("=")) {
      summary = summary.trim().split("=")[0] + summary.trim().split("=")[1];
    }
    summary = summary.substring(5);
    // boolean hasPda = summary.contains("PDA");
    String[] params = summary.trim().split("PV");
    MoveData result = new MoveData();
    if (params.length <= 2) {
      String[] params2 = params[0].trim().split(" ");
      if (params2.length >= 8) {
        result.isKataData = true;
        Lizzie.board.isKataBoard = true;
        result.playouts = Integer.parseInt(params2[1]);
        result.winrate = Double.parseDouble(params2[3].replace("%", ""));
        result.scoreMean = Double.parseDouble(params2[5]);
        result.scoreStdev = Double.parseDouble(params2[7]);
      }
      if (params.length == 2) {
        result.variation =
            Arrays.asList(params[1].trim().split(" ", Lizzie.config.limitBranchLength));
        result.coordinate = result.variation.get(0);
      } else {
        result.coordinate = "A1";
        result.variation = Arrays.asList("A1");
      }
    }

    //	      result.coordinate = match.group(1);
    //	      result.playouts = Integer.parseInt(match.group(2));
    //	      result.winrate = Double.parseDouble(match.group(Lizzie.config.showlcbwinrate ? 4 : 3));
    //	      result.variation = Arrays.asList(match.group(5).split(" ",
    // Lizzie.config.limitBranchLength));
    // result.variation = Arrays.asList(match.group(5).split(" "));
    return result;
  }

  /**
   * Parses a leelaz summary output line. For example:
   *
   * <p>0.15 0.16
   *
   * <p>P16 -> 4 (V: 50.94%) (N: 5.79%) PV: P16 N18 R5 Q5 D4 -> 1393 (V: 51.16%) (N: 58.90%) PV: D4
   * D17 Q4 C6 F3 C12 K17 O17 G17 F16 E18 G16 E17 E16 H17 D18 D16 E19 F17 D15 C16 B17 B16 C17
   *
   * <p>0.17
   *
   * <p>Q4 -> 4348 (V: 43.88%) (LCB: 43.81%) (N: 18.67%) PV: Q4 D16 D4 Q16 R14 R6 C1
   *
   * @param summary line of summary output
   */
  public static MoveData fromSummary(String summary) {
    Matcher match = summaryPattern.matcher(summary.trim());
    if (!match.matches()) {
      Matcher matchold = summaryPatternold.matcher(summary.trim());
      if (!matchold.matches()) {
        Lizzie.gtpConsole.addLine("Summary err");
        return null;
        // throw new IllegalArgumentException("Unexpected summary format: " + summary);
      } else {
        MoveData result = new MoveData();
        result.coordinate = matchold.group(1);
        result.playouts = Integer.parseInt(matchold.group(2));
        result.winrate = Double.parseDouble(matchold.group(3));
        result.variation =
            Arrays.asList(matchold.group(4).split(" ", Lizzie.config.limitBranchLength));
        // result.variation = Arrays.asList(matchold.group(4).split(" "));
        return result;
      }
    } else {
      MoveData result = new MoveData();
      result.coordinate = match.group(1);
      result.playouts = Integer.parseInt(match.group(2));
      result.winrate = Double.parseDouble(match.group(3));
      result.variation = Arrays.asList(match.group(5).split(" ", Lizzie.config.limitBranchLength));
      // result.variation = Arrays.asList(match.group(5).split(" "));
      return result;
    }
  }

  public static MoveData fromSummaryLeela0110(String summary) {

    // support 0.16 0.15
    Pattern oldPattern = summaryPatternLeela0110;
    Matcher matchold = oldPattern.matcher(summary.trim());
    if (!matchold.matches()) {
      throw new IllegalArgumentException("Unexpected summary format: " + summary);
    } else {
      MoveData result = new MoveData();
      result.coordinate = matchold.group(1);
      result.playouts = Integer.parseInt(matchold.group(2));
      result.winrate = Double.parseDouble(matchold.group(3));
      // result.oriwinrate = result.winrate;
      result.policy = Double.parseDouble(matchold.group(4));
      result.variation =
          Arrays.asList(matchold.group(5).split(" ", Lizzie.config.limitBranchLength));
      return result;
    }
  }

  public static MoveData fromSummarySai(String summary) {
    Matcher match = summaryPatternSai.matcher(summary.trim());
    if (match.matches()) {
      MoveData result = new MoveData();
      result.coordinate = match.group(1);
      result.playouts = Integer.parseInt(match.group(2));
      result.winrate = Double.parseDouble(match.group(3));
      result.scoreMean =
          Lizzie.board.getHistory().isBlacksTurn()
              ? Double.parseDouble(match.group(5))
              : -Double.parseDouble(match.group(5));
      result.variation = Arrays.asList(match.group(6).split(" ", Lizzie.config.limitBranchLength));
      result.isKataData = true;
      result.isSaiData = true;
      return result;
    } else {
      Lizzie.gtpConsole.addLine("Summary err");
      return null;
    }
  }

  // C15  ->      718, 47.87%, C15 O17 R14 Q18 R6 O3 M17 R17 P17 P18
  public static MoveData fromSummaryZen(String summary) {
    String[] params = summary.trim().split(",");

    if (params.length == 3) {
      MoveData result = new MoveData();
      String[] params1 = params[0].trim().split("->");
      if (params1.length == 2) {
        result.coordinate = params1[0].trim();
        result.playouts = Integer.parseInt(params1[1].trim());
      }
      String wr = params[1].trim();
      result.winrate = Double.parseDouble(wr.substring(0, wr.length() - 1));
      result.variation =
          Arrays.asList(params[2].trim().split(" ", Lizzie.config.limitBranchLength));
      return result;
    } else return null;
  }

  private static Pattern summaryPatternLeela0110 =
      Pattern.compile(
          "^ *(\\w\\d*) -> *(\\d+) \\(W: ([^%)]+)%\\).* \\(N: ([^%)]+)%\\) PV: (.+).*$");

  private static Pattern summaryPattern =
      Pattern.compile(
          "^ *(\\w\\d*) -> *(\\d+) \\(V: ([^%)]+)%\\) \\(LCB: ([^%)]+)%\\) \\([^\\)]+\\) PV: (.+).*$");
  private static Pattern summaryPatternold =
      Pattern.compile("^ *(\\w\\d*) -> *(\\d+) \\(V: ([^%)]+)%\\) \\([^\\)]+\\) PV: (.+).*$");

  private static Pattern summaryPatternSai =
      Pattern.compile(
          "^ *(\\w\\d*) -> *(\\d+) \\(V: ([^%)]+)%\\) \\(LCB: ([^%)]+)%\\) \\([^\\)]+\\) \\(A: ([^)]+)\\) PV: (.+).*$");

  public static int getPlayouts(List<MoveData> moves) {
    int playouts = 0;
    for (MoveData move : moves) {
      if (move.isSymmetry) continue;
      playouts += move.playouts;
    }
    return playouts;
  }

  public static Comparator policyComparator =
      new Comparator() {
        @Override
        public int compare(Object o1, Object o2) {
          MoveData e1 = (MoveData) o1;
          MoveData e2 = (MoveData) o2;
          if (e1.policy > e2.policy) return 1;
          if (e1.policy < e2.policy) return -1;
          else return 0;
        }
      };
}
