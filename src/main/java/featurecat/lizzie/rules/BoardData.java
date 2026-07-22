package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.*;

public class BoardData {
  public int moveNumber;
  public int moveMNNumber;
  public Optional<int[]> lastMove;
  public int[] moveNumberList;
  public boolean blackToPlay;
  public boolean dummy;
  // added for change bestmoves when playouts is not increased

  public Stone lastMoveColor;
  public Stone[] stones;
  public Zobrist zobrist;
  public boolean verify;

  public double winrate;
  public double winrate2;
  private int playouts;
  private int playouts2;
  public double scoreMean;
  public double scoreMean2;
  public double scoreStdev;
  public double scoreStdev2;
  // public double scoreMeanBoard;
  // public double scoreMeanBoard2;
  public List<MoveData> bestMoves;
  public List<MoveData> bestMovesOutOfRange;
  public List<MoveData> bestMoves2;
  public List<MoveData> bestMoves2OutOfRange;
  public int blackCaptures;
  public int whiteCaptures;
  public boolean isChanged = false;
  public boolean isChanged2 = false;
  public String comment = "";
  // public String comment2 = "";
  public String engineName = "";
  public String engineName2 = "";
  public boolean isSaiData;
  public boolean isSaiData2;
  public boolean isKataData;
  public boolean isKataData2;
  public int analysisHeaderSlots;
  public int analysisHeaderSlots2;
  //  public boolean isPDA;
  //  public boolean isPDA2;
  public double pda = 0;
  public double pda2 = 0;
  public double komi = -999;
  public double wrn = 0;
  public List<Double> estimateArray;
  public List<Double> estimateArray2;
  public boolean playoutsChanged;
  public int lastMoveMatchCandidteNo;
  //	public boolean commented=true;
  //	public boolean commented2=true;

  private BoardNodeKind nodeKind;

  // Node properties
  private Map<String, String> properties = new LinkedHashMap<String, String>();

  private BoardData(
      BoardNodeKind nodeKind,
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      Zobrist zobrist,
      int moveNumber,
      int[] moveNumberList,
      int blackCaptures,
      int whiteCaptures,
      double winrate,
      int playouts) {
    this.nodeKind = Objects.requireNonNull(nodeKind, "nodeKind");
    this.moveMNNumber = -1;
    this.moveNumber = moveNumber;
    this.lastMove = copyLastMove(Objects.requireNonNull(lastMove, "lastMove"));
    validateNodeKind(this.nodeKind, this.lastMove);
    this.moveNumberList = copyIntArray(moveNumberList);
    this.blackToPlay = blackToPlay;
    this.dummy = false;
    this.lastMoveColor = lastMoveColor;
    this.stones = copyStones(stones);
    this.zobrist = zobrist == null ? null : zobrist.clone();
    this.verify = false;

    this.winrate = winrate;
    this.playouts = playouts;
    this.blackCaptures = blackCaptures;
    this.whiteCaptures = whiteCaptures;
    this.bestMoves = new ArrayList<>();
    this.bestMoves2 = new ArrayList<>();
  }

  public double getKomi() {
    if (komi != -999) return komi;
    else return Lizzie.board.getHistory().getGameInfo().getKomi();
  }

  public static BoardData empty(int width, int height) {
    Stone[] stones = new Stone[width * height];
    for (int i = 0; i < stones.length; i++) {
      stones[i] = Stone.EMPTY;
    }

    int[] boardArray = new int[width * height];
    return snapshot(
        stones, Optional.empty(), Stone.EMPTY, true, new Zobrist(), 0, boardArray, 0, 0, 50, 0);
  }

  /** Creates an explicit history move node. */
  public static BoardData move(
      Stone[] stones,
      int[] lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      Zobrist zobrist,
      int moveNumber,
      int[] moveNumberList,
      int blackCaptures,
      int whiteCaptures,
      double winrate,
      int playouts) {
    return new BoardData(
        BoardNodeKind.MOVE,
        stones,
        Optional.of(Objects.requireNonNull(lastMove, "lastMove")),
        lastMoveColor,
        blackToPlay,
        zobrist,
        moveNumber,
        moveNumberList,
        blackCaptures,
        whiteCaptures,
        winrate,
        playouts);
  }

  /** Creates an explicit history pass node. */
  public static BoardData pass(
      Stone[] stones,
      Stone lastMoveColor,
      boolean blackToPlay,
      Zobrist zobrist,
      int moveNumber,
      int[] moveNumberList,
      int blackCaptures,
      int whiteCaptures,
      double winrate,
      int playouts) {
    return new BoardData(
        BoardNodeKind.PASS,
        stones,
        Optional.empty(),
        lastMoveColor,
        blackToPlay,
        zobrist,
        moveNumber,
        moveNumberList,
        blackCaptures,
        whiteCaptures,
        winrate,
        playouts);
  }

  /**
   * Creates an explicit snapshot node.
   *
   * <p>Sync input never carries a real pass signal. Markerless sync callers must pass {@link
   * Optional#empty()} and keep the node canonical as {@link BoardNodeKind#SNAPSHOT}.
   */
  public static BoardData snapshot(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      Zobrist zobrist,
      int moveNumber,
      int[] moveNumberList,
      int blackCaptures,
      int whiteCaptures,
      double winrate,
      int playouts) {
    return new BoardData(
        BoardNodeKind.SNAPSHOT,
        stones,
        Objects.requireNonNull(lastMove, "lastMove"),
        lastMoveColor,
        blackToPlay,
        zobrist,
        moveNumber,
        moveNumberList,
        blackCaptures,
        whiteCaptures,
        winrate,
        playouts);
  }

  /** Returns the canonical node kind for this board state. */
  public BoardNodeKind getNodeKind() {
    return nodeKind;
  }

  public boolean isMoveNode() {
    return getNodeKind() == BoardNodeKind.MOVE;
  }

  public boolean isPassNode() {
    return getNodeKind() == BoardNodeKind.PASS;
  }

  public boolean isSnapshotNode() {
    return getNodeKind() == BoardNodeKind.SNAPSHOT;
  }

  public boolean isHistoryActionNode() {
    return getNodeKind().isHistoryAction();
  }

  /**
   * Add a key and value
   *
   * @param key
   * @param value
   */
  public void addProperty(String key, String value) {
    SGFParser.addProperty(properties, key, value);
    if ("N".equals(key) && comment.isEmpty()) {
      comment = value;
    } else if ("MN".equals(key)) {
      moveMNNumber = Integer.parseInt(getOrDefault("MN", "-1"));
    }
  }

  /**
   * Get a value with key
   *
   * @param key
   * @return
   */
  public String getProperty(String key) {
    return properties.get(key);
  }

  /**
   * Get a value with key, or the default if there is no such key
   *
   * @param key
   * @param defaultValue
   * @return
   */
  public String getOrDefault(String key, String defaultValue) {
    return SGFParser.getOrDefault(properties, key, defaultValue);
  }

  /**
   * Get the properties
   *
   * @return
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  public void setProperties(Map<String, String> properties) {
    this.properties = copyProperties(properties);
  }

  /**
   * Add the properties
   *
   * @return
   */
  public void addProperties(Map<String, String> addProps) {
    SGFParser.addProperties(this.properties, addProps);
  }

  /**
   * Add the properties from string
   *
   * @return
   */
  public void addProperties(String propsStr) {
    SGFParser.addProperties(properties, propsStr);
  }

  /**
   * Get properties string
   *
   * @return
   */
  public String propertiesString() {
    return SGFParser.propertiesString(properties);
  }

  public double getWinrate() {
    if (!blackToPlay || !Lizzie.config.winrateAlwaysBlack) {
      return winrate;
    } else {
      return 100 - winrate;
    }
  }

  public double getWinrate2() {
    if (!blackToPlay || !Lizzie.config.winrateAlwaysBlack) {
      return winrate2;
    } else {
      return 100 - winrate2;
    }
  }

  public void tryToSetBestMoves(
      List<MoveData> moves, String engName, boolean isFromLeelaz, int totalplayouts) {
    tryToSetBestMoves(moves, engName, isFromLeelaz, totalplayouts, null);
  }

  public void tryToSetBestMoves2(
      List<MoveData> moves, String engName, boolean isFromLeelaz, int totalplayouts) {
    tryToSetBestMoves2(moves, engName, isFromLeelaz, totalplayouts, null);
  }

  public void tryToSetBestMoves(
      List<MoveData> moves,
      String engName,
      boolean isFromLeelaz,
      int totalplayouts,
      List<Double> estimateArray) {
    tryToSetBestMoves(moves, engName, isFromLeelaz, totalplayouts, estimateArray, false);
  }

  public void tryToSetBestMoves(
      List<MoveData> moves,
      String engName,
      boolean isFromLeelaz,
      int totalplayouts,
      List<Double> estimateArray,
      boolean forceOverride) {
    if (!forceOverride
        && Lizzie.config.enableLizzieCache
        && !Lizzie.config.isAutoAna
        && !EngineManager.isEngineGame) {
      if (!(totalplayouts > playouts || isChanged || pda != Lizzie.leelaz.pda)) {
        if (estimateArray == null || this.estimateArray != null) return;
      }
    }
    // added for change bestmoves when playouts is not increased
    if (totalplayouts < playouts) isChanged = false;
    setPlayouts(totalplayouts);
    playoutsChanged = true;
    this.estimateArray = compactEstimateArray(estimateArray);
    winrate = moves.get(0).winrate;
    if (moves.get(0).isKataData) {
      scoreMean = moves.get(0).scoreMean;
      scoreStdev = moves.get(0).scoreStdev;
      if (isFromLeelaz) {
        Lizzie.leelaz.scoreMean = moves.get(0).scoreMean;
        Lizzie.leelaz.scoreStdev = moves.get(0).scoreStdev;
      }
      isKataData = true;
    } else isKataData = false;
    isSaiData = moves.get(0).isSaiData;
    engineName = engName;
    komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    if (isFromLeelaz) {
      if (Lizzie.leelaz.isDymPda || Lizzie.leelaz.pda != 0) {
        pda = Lizzie.leelaz.pda;
      } else pda = 0;
    }
    if (!(EngineManager.isEngineGame && EngineManager.engineGameInfo.isGenmove))
      wrn = Lizzie.leelaz.wrn;
    analysisHeaderSlots = 0;
    // 排序
    Collections.sort(
        moves,
        new Comparator<MoveData>() {

          @Override
          public int compare(MoveData s1, MoveData s2) {
            // 降序
            if (s1.order < s2.order) return -1;
            if (s1.order > s2.order) return 1;
            return 0;
          }
        });

    tryToLimitMoves(moves, bestMoves, true);
    bestMoves = moves;
  }

  private void tryToLimitMoves(List<MoveData> moves, List<MoveData> lastMoves, boolean isMain) {
    // TODO Auto-generated method stub
    List<MoveData> outOfRangeMoves = new ArrayList<>();
    if (Lizzie.config.limitMaxSuggestion > 0
        && !Lizzie.config.showNoSuggCircle
        && (moves.size() > Lizzie.config.limitMaxSuggestion)) {
      for (int n = Lizzie.config.limitMaxSuggestion; n < moves.size(); n++) {
        MoveData move = moves.get(n);
        boolean needSkip = false;
        int absoluteMaxSuggestionOrder = Lizzie.config.limitMaxSuggestion + 1;
        if (move.order < absoluteMaxSuggestionOrder) {
          for (int s = 0; s < absoluteMaxSuggestionOrder && s < lastMoves.size(); s++) {
            MoveData lastBestMove = lastMoves.get(s);
            if (s >= Lizzie.config.limitMaxSuggestion) {
              if (!lastBestMove.lastTimeUnlimited) continue;
            }
            if (move.coordinate.equals(lastBestMove.coordinate)) {
              move.lastTimeUnlimited = true;
              if (move.playouts > lastBestMove.playouts || !lastBestMove.lastTimeUnlimited) {
                move.lastTimeUnlimitedTime = System.currentTimeMillis();
                needSkip = true;
              } else if (System.currentTimeMillis() - lastBestMove.lastTimeUnlimitedTime < 3000) {
                move.lastTimeUnlimitedTime = lastBestMove.lastTimeUnlimitedTime;
                needSkip = true;
              }
              continue;
            }
          }
        }
        if (Lizzie.frame.priorityMoveCoords.size() > 0 && !needSkip) {
          for (String coords : Lizzie.frame.priorityMoveCoords) {
            if (move.coordinate.equals(coords)) {
              needSkip = true;
              continue;
            }
          }
        }
        if (!needSkip) {
          outOfRangeMoves.add(move);
          moves.remove(move);
          n--;
        }
      }
      if (isMain) bestMovesOutOfRange = outOfRangeMoves;
      else bestMoves2OutOfRange = outOfRangeMoves;
    }
  }

  public void tryToSetBestMoves2(
      List<MoveData> moves,
      String engName,
      boolean isFromLeelaz,
      int totalplayouts,
      List<Double> estimateArray) {
    if (Lizzie.config.enableLizzieCache && !Lizzie.config.isAutoAna) {
      if (!(totalplayouts > playouts2
          || isChanged2
          || pda != Lizzie.leelaz.pda)) { // ||Lizzie.frame.urlSgf
        if (estimateArray == null || this.estimateArray != null) return;
      }
    }
    if (totalplayouts < playouts2) isChanged2 = false;
    setPlayouts2(totalplayouts);
    this.estimateArray2 = compactEstimateArray(estimateArray);
    winrate2 = moves.get(0).winrate;
    if (moves.get(0).isKataData) {
      scoreMean2 = moves.get(0).scoreMean;
      scoreStdev2 = moves.get(0).scoreStdev;
      if (Lizzie.leelaz2 != null && isFromLeelaz) {
        Lizzie.leelaz2.scoreMean = moves.get(0).scoreMean;
        Lizzie.leelaz2.scoreStdev = moves.get(0).scoreStdev;
      }
      isKataData2 = true;
    } else isKataData2 = false;
    isSaiData2 = moves.get(0).isSaiData;
    engineName2 = engName;
    if (isFromLeelaz) {
      if (Lizzie.leelaz2 != null && (Lizzie.leelaz2.isDymPda || Lizzie.leelaz2.pda != 0)) {
        pda2 = Lizzie.leelaz2.pda;
      } else pda2 = 0;
    }
    analysisHeaderSlots2 = 0;
    Collections.sort(
        moves,
        new Comparator<MoveData>() {

          @Override
          public int compare(MoveData s1, MoveData s2) {
            // 降序
            if (s1.order < s2.order) return -1;
            if (s1.order > s2.order) return 1;
            return 0;
          }
        });
    tryToLimitMoves(moves, bestMoves2, false);
    bestMoves2 = moves;
  }

  public static double getWinrateFromBestMoves(List<MoveData> bestMoves) {
    // return the weighted average winrate of bestMoves
    double winrate = 0;
    try {
      winrate = bestMoves.get(0).winrate;
    } catch (Exception e) {
    }
    return winrate;
    //    return bestMoves
    //        .stream()
    //        .mapToDouble(move -> move.winrate * move.playouts / MoveData.getPlayouts(bestMoves))
    //        .sum();
  }

  public static double getScoreLeadFromBestMoves(List<MoveData> bestMoves) {
    // return the weighted average winrate of bestMoves
    double scoreLead = 0;
    try {
      scoreLead = bestMoves.get(0).scoreMean;
    } catch (Exception e) {
    }
    return scoreLead;
  }

  public String bestMovesToString() {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (MoveData move : bestMoves) {
      i++;
      if (LizzieFrame.isShareing && i > 10) break;
      // eg: info move R5 visits 38 winrate 5404 pv R5 Q5 R6 S4 Q10 C3 D3 C4 C6 C5 D5
      sb.append("move ").append(move.coordinate);
      sb.append(" visits ").append(move.playouts);
      sb.append(" winrate ").append((int) (move.winrate * 100));
      sb.append(" prior ").append((int) (move.policy * 100));
      if (isKataData)
        sb.append(" scoreMean ").append(String.format(Locale.ENGLISH, "%.2f", move.scoreMean));
      sb.append(" pv ")
          .append(
              move.variation == null
                  ? ""
                  : move.variation.stream().reduce((a, b) -> a + " " + b).get());
      if (isKataData && move.pvVisits != null)
        sb.append(" pvVisits ").append(move.pvVisits.stream().reduce((a, b) -> a + " " + b).get());
      if (i < bestMoves.size())
        sb.append(" info "); // this order is just because of how the MoveData info parser works
    }
    if (estimateArray != null && !estimateArray.isEmpty()) {}

    return sb.toString();
  }

  public String bestMovesToString2() {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (MoveData move : bestMoves2) {
      i++;
      if (LizzieFrame.isShareing && i > 10) break;
      // eg: info move R5 visits 38 winrate 5404 pv R5 Q5 R6 S4 Q10 C3 D3 C4 C6 C5 D5
      sb.append("move ").append(move.coordinate);
      sb.append(" visits ").append(move.playouts);
      sb.append(" winrate ").append((int) (move.winrate * 100));
      sb.append(" prior ").append((int) (move.policy * 100));
      if (isKataData2) sb.append(" scoreMean ").append(move.scoreMean);
      sb.append(" pv ").append(move.variation.stream().reduce((a, b) -> a + " " + b).get());
      sb.append(" info "); // this order is just because of how the MoveData info parser works
    }
    return sb.toString();
  }

  public void setPlayouts(int playouts) {
    // if (playouts > this.playouts || isChanged) {
    this.playouts = playouts;
    // }
  }

  public void setPlayouts2(int playouts) {
    // if (playouts > this.playouts || isChanged) {
    this.playouts2 = playouts;
    // }
  }

  public void setScoreMean(double scoreMean) {
    // if (playouts > this.playouts || isChanged) {
    this.scoreMean = scoreMean;
    // }
  }

  public void setScoreMean2(double scoreMean) {
    // if (playouts > this.playouts || isChanged) {
    this.scoreMean2 = scoreMean;
    // }
  }

  public void setPlayoutsForce(int playouts) {
    this.playouts = playouts;
  }

  public int getPlayouts() {
    return playouts;
  }

  public int getPlayouts2() {
    return playouts2;
  }

  public void sync(BoardData data) {
    copyCoreStateFrom(data);
    copyAnalysisStateFrom(data, true);
    this.properties = copyProperties(data.properties);
  }

  public void copyAnalysisPayloadFrom(BoardData data) {
    copyAnalysisStateFrom(data, false);
  }

  public BoardData clone() {
    BoardData data = copyCoreData();
    data.copyCoreStateFrom(this);
    data.copyAnalysisStateFrom(this, true);
    data.properties = copyProperties(this.properties);
    return data;
  }

  public boolean hasPrimaryAnalysisPayload() {
    return getPlayouts() > 0
        || analysisHeaderSlots > 0
        || (engineName != null && !engineName.isEmpty())
        || (bestMoves != null && !bestMoves.isEmpty())
        || isKataData
        || (estimateArray != null && !estimateArray.isEmpty());
  }

  public boolean hasSecondaryAnalysisPayload() {
    return getPlayouts2() > 0
        || analysisHeaderSlots2 > 0
        || (engineName2 != null && !engineName2.isEmpty())
        || (bestMoves2 != null && !bestMoves2.isEmpty())
        || isKataData2
        || (estimateArray2 != null && !estimateArray2.isEmpty());
  }

  public boolean hasAnyAnalysisPayload() {
    return hasPrimaryAnalysisPayload() || hasSecondaryAnalysisPayload();
  }

  public boolean isSameCoord(int[] coord) {
    if (coord == null || coord.length < 2 || !this.lastMove.isPresent()) {
      return false;
    }
    return this.lastMove.map(m -> (m[0] == coord[0] && m[1] == coord[1])).orElse(false);
  }

  public void tryToClearBestMoves() {
    clearPrimaryAnalysisPayloadState();
    clearSecondaryAnalysisPayloadState();
    if (Lizzie.leelaz.isKatago) {
      Lizzie.leelaz.scoreMean = 0;
      Lizzie.leelaz.scoreStdev = 0;
    }
    if (Lizzie.leelaz2 != null && Lizzie.leelaz2.isKatago) {
      Lizzie.leelaz2.scoreMean = 0;
      Lizzie.leelaz2.scoreStdev = 0;
    }
  }

  public void clearAnalysisPayloadState() {
    clearPrimaryAnalysisPayloadState();
    clearSecondaryAnalysisPayloadState();
  }

  private void clearPrimaryAnalysisPayloadState() {
    engineName = "";
    winrate = 50;
    setPlayouts(0);
    bestMoves = new ArrayList<MoveData>();
    bestMovesOutOfRange = new ArrayList<MoveData>();
    estimateArray = null;
    isSaiData = false;
    isKataData = false;
    isChanged = false;
    playoutsChanged = false;
    analysisHeaderSlots = 0;
    scoreMean = 0;
    scoreStdev = 0;
    pda = 0;
  }

  private void clearSecondaryAnalysisPayloadState() {
    engineName2 = "";
    winrate2 = 50;
    setPlayouts2(0);
    bestMoves2 = new ArrayList<MoveData>();
    bestMoves2OutOfRange = new ArrayList<MoveData>();
    estimateArray2 = null;
    isSaiData2 = false;
    isKataData2 = false;
    isChanged2 = false;
    analysisHeaderSlots2 = 0;
    scoreMean2 = 0;
    scoreStdev2 = 0;
    pda2 = 0;
  }

  private static void validateNodeKind(BoardNodeKind nodeKind, Optional<int[]> lastMove) {
    if (nodeKind == BoardNodeKind.MOVE && !lastMove.isPresent()) {
      throw new IllegalArgumentException("MOVE nodes require coordinates.");
    }
    if (nodeKind == BoardNodeKind.PASS && lastMove.isPresent()) {
      throw new IllegalArgumentException("PASS nodes cannot carry coordinates.");
    }
  }

  private void copyCoreStateFrom(BoardData data) {
    this.nodeKind = data.getNodeKind();
    this.moveMNNumber = data.moveMNNumber;
    this.moveNumber = data.moveNumber;
    this.lastMove = copyLastMove(data.lastMove);
    this.moveNumberList = copyIntArray(data.moveNumberList);
    this.blackToPlay = data.blackToPlay;
    this.dummy = data.dummy;
    this.lastMoveColor = data.lastMoveColor;
    this.stones = copyStones(data.stones);
    this.zobrist = data.zobrist == null ? null : data.zobrist.clone();
    this.verify = data.verify;
    this.blackCaptures = data.blackCaptures;
    this.whiteCaptures = data.whiteCaptures;
  }

  private void copyAnalysisStateFrom(BoardData data, boolean includeComment) {
    this.winrate = data.winrate;
    this.winrate2 = data.winrate2;
    this.playouts = data.playouts;
    this.playouts2 = data.playouts2;
    this.scoreMean = data.scoreMean;
    this.scoreMean2 = data.scoreMean2;
    this.scoreStdev = data.scoreStdev;
    this.scoreStdev2 = data.scoreStdev2;
    this.bestMoves = copyMoveDataList(data.bestMoves);
    this.bestMovesOutOfRange = copyMoveDataList(data.bestMovesOutOfRange);
    this.bestMoves2 = copyMoveDataList(data.bestMoves2);
    this.bestMoves2OutOfRange = copyMoveDataList(data.bestMoves2OutOfRange);
    this.isChanged = data.isChanged;
    this.isChanged2 = data.isChanged2;
    if (includeComment) {
      this.comment = data.comment;
    }
    this.engineName = data.engineName;
    this.engineName2 = data.engineName2;
    this.isSaiData = data.isSaiData;
    this.isSaiData2 = data.isSaiData2;
    this.isKataData = data.isKataData;
    this.isKataData2 = data.isKataData2;
    this.analysisHeaderSlots = data.analysisHeaderSlots;
    this.analysisHeaderSlots2 = data.analysisHeaderSlots2;
    this.pda = data.pda;
    this.pda2 = data.pda2;
    this.komi = data.komi;
    this.wrn = data.wrn;
    this.estimateArray = copyDoubleList(data.estimateArray);
    this.estimateArray2 = copyDoubleList(data.estimateArray2);
    this.playoutsChanged = data.playoutsChanged;
    this.lastMoveMatchCandidteNo = data.lastMoveMatchCandidteNo;
  }

  private BoardData copyCoreData() {
    Stone[] stonesCopy = copyStones(this.stones);
    Optional<int[]> lastMoveCopy = copyLastMove(this.lastMove);
    int[] moveNumberListCopy = copyIntArray(this.moveNumberList);
    Zobrist zobristCopy = this.zobrist == null ? null : this.zobrist.clone();
    if (this.nodeKind == BoardNodeKind.MOVE) {
      return BoardData.move(
          stonesCopy,
          lastMoveCopy.orElseThrow(),
          this.lastMoveColor,
          this.blackToPlay,
          zobristCopy,
          this.moveNumber,
          moveNumberListCopy,
          this.blackCaptures,
          this.whiteCaptures,
          this.winrate,
          this.playouts);
    }
    if (this.nodeKind == BoardNodeKind.PASS) {
      return BoardData.pass(
          stonesCopy,
          this.lastMoveColor,
          this.blackToPlay,
          zobristCopy,
          this.moveNumber,
          moveNumberListCopy,
          this.blackCaptures,
          this.whiteCaptures,
          this.winrate,
          this.playouts);
    }
    return BoardData.snapshot(
        stonesCopy,
        lastMoveCopy,
        this.lastMoveColor,
        this.blackToPlay,
        zobristCopy,
        this.moveNumber,
        moveNumberListCopy,
        this.blackCaptures,
        this.whiteCaptures,
        this.winrate,
        this.playouts);
  }

  private static Optional<int[]> copyLastMove(Optional<int[]> move) {
    return move.isPresent() ? Optional.of(move.get().clone()) : Optional.empty();
  }

  private static Stone[] copyStones(Stone[] stones) {
    return stones == null ? null : stones.clone();
  }

  private static int[] copyIntArray(int[] values) {
    return values == null ? null : values.clone();
  }

  public static List<Double> compactEstimateArray(List<Double> values) {
    return CompactDoubleList.copyOf(values);
  }

  public static List<Double> compactEstimateArray(double[] values, int size) {
    return CompactDoubleList.copyOf(values, size);
  }

  private static List<Double> copyDoubleList(List<Double> values) {
    return compactEstimateArray(values);
  }

  private static List<MoveData> copyMoveDataList(List<MoveData> moves) {
    return moves == null ? null : new ArrayList<MoveData>(moves);
  }

  private static Map<String, String> copyProperties(Map<String, String> properties) {
    return properties == null
        ? new LinkedHashMap<String, String>()
        : new LinkedHashMap<String, String>(properties);
  }
}
