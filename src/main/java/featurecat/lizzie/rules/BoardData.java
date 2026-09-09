package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.EngineObservation;
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
  /** Exact root counts; -1 denotes legacy/unknown totals in the corresponding slot. */
  public int rootVisits = -1;
  public int rootVisits2 = -1;
  private Object analysisSource;
  private Object analysisSource2;

  public enum AnalysisAdoption { REJECTED, OWNERSHIP_ONLY, FULL }

  /** Called only after the engine has revalidated the exact ordinary output owner. */
  public AnalysisAdoption adoptOrdinaryAnalysis(
      List<MoveData> moves, String engineName, Leelaz engine, int totalVisits,
      int exactRootVisits, List<Double> ownership, Object source, boolean secondary,
      boolean forceFull) {
    if (moves == null || moves.isEmpty()) return AnalysisAdoption.REJECTED;
    int cached = secondary ? playouts2 : playouts;
    int cachedRoot = secondary ? rootVisits2 : rootVisits;
    Object cachedSource = secondary ? analysisSource2 : analysisSource;
    boolean invalidated = secondary ? isChanged2 : isChanged;
    double cachedPda = secondary ? pda2 : pda;
    boolean sameSource = source != null && source == cachedSource;
    boolean comparable = cachedRoot < 0 || exactRootVisits >= 0;
    int comparisonVisits = cachedRoot < 0 && exactRootVisits >= 0
        ? MoveData.getPlayouts(moves) : totalVisits;
    boolean protectedCache = Lizzie.config.enableLizzieCache && !Lizzie.config.isAutoAna
        && (secondary || !Lizzie.engineGame.current().playing());
    boolean full = forceFull || !protectedCache || invalidated || cachedPda != engine.pda
        || (comparable && comparisonVisits > cached)
        || (sameSource && cachedRoot >= 0 && exactRootVisits >= 0 && totalVisits == cached);
    if (!full) {
      List<Double> oldOwnership = secondary ? estimateArray2 : estimateArray;
      if (ownership != null && !ownership.isEmpty()
          && (!comparable || comparisonVisits < cached || oldOwnership == null)) {
        if (secondary) estimateArray2 = compactEstimateArray(ownership);
        else estimateArray = compactEstimateArray(ownership);
        return AnalysisAdoption.OWNERSHIP_ONLY;
      }
      return AnalysisAdoption.REJECTED;
    }
    List<Double> retainedOwnership = ownership;
    if (sameSource && (ownership == null || ownership.isEmpty())) {
      retainedOwnership = secondary ? estimateArray2 : estimateArray;
    }
    moves.sort(Comparator.comparingInt(move -> move.order));
    if (secondary) {
      tryToSetBestMoves2FromEngine(
          moves, engineName, true, engine, totalVisits, retainedOwnership, true);
      rootVisits2 = exactRootVisits;
      analysisSource2 = source;
      isChanged2 = false;
    } else {
      tryToSetBestMovesFromEngine(
          moves, engineName, engine, totalVisits, retainedOwnership, true);
      rootVisits = exactRootVisits;
      analysisSource = source;
      isChanged = false;
    }
    return AnalysisAdoption.FULL;
  }
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
  public boolean scoreMeanIsBlackPerspective;
  public boolean scoreMeanIsBlackPerspective2;
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
    tryToSetBestMovesWithStatus(
        moves, engName, isFromLeelaz, totalplayouts, estimateArray, forceOverride);
  }

  public boolean tryToSetBestMovesWithStatus(
      List<MoveData> moves,
      String engName,
      boolean isFromLeelaz,
      int totalplayouts,
      List<Double> estimateArray,
      boolean forceOverride) {
    return tryToSetBestMovesWithStatusFromEngine(
        moves,
        engName,
        isFromLeelaz ? Lizzie.leelaz : null,
        totalplayouts,
        estimateArray,
        forceOverride);
  }

  /**
   * Publishes main-analysis metadata from an explicit engine owner. This avoids routing delayed
   * exact-engine output through whichever engine happens to be globally primary at publication
   * time.
   */
  public boolean tryToSetBestMovesFromEngine(
      List<MoveData> moves,
      String engName,
      Leelaz sourceEngine,
      int totalplayouts,
      List<Double> estimateArray,
      boolean forceOverride) {
    return tryToSetBestMovesWithStatusFromEngine(
        moves, engName, sourceEngine, totalplayouts, estimateArray, forceOverride);
  }

  private boolean tryToSetBestMovesWithStatusFromEngine(
      List<MoveData> moves,
      String engName,
      Leelaz sourceEngine,
      int totalplayouts,
      List<Double> estimateArray,
      boolean forceOverride) {
    Leelaz metadataEngine = sourceEngine != null ? sourceEngine : Lizzie.leelaz;
    if (moves == null || moves.isEmpty()) {
      return false;
    }
    if (!forceOverride
        && Lizzie.config.enableLizzieCache
        && !Lizzie.config.isAutoAna
        && !Lizzie.engineGame.current().playing()) {
      if (!(totalplayouts > playouts
          || isChanged
          || (metadataEngine != null && pda != metadataEngine.pda))) {
        if (totalplayouts < playouts) {
          // Requesting ownership restarts KataGo's stream at low visits. Backfill only the map.
          if (estimateArray == null || estimateArray.isEmpty()) {
            traceAnalysisCacheDecision(
                metadataEngine,
                engName,
                totalplayouts,
                moves,
                playouts,
                winrate,
                scoreMean,
                "REJECT",
                "LOWER_VISITS");
            return false;
          }
          traceAnalysisCacheDecision(
              metadataEngine,
              engName,
              totalplayouts,
              moves,
              playouts,
              winrate,
              scoreMean,
              "ACCEPT",
              "OWNERSHIP_BACKFILL");
          this.estimateArray = compactEstimateArray(estimateArray);
          return true;
        }
        if (estimateArray == null || estimateArray.isEmpty() || this.estimateArray != null) {
          traceAnalysisCacheDecision(
              metadataEngine,
              engName,
              totalplayouts,
              moves,
              playouts,
              winrate,
              scoreMean,
              "REJECT",
              "EQUAL_VISITS");
          return false;
        }
      }
    }
    traceAnalysisCacheDecision(
        metadataEngine,
        engName,
        totalplayouts,
        moves,
        playouts,
        winrate,
        scoreMean,
        "ACCEPT",
        primaryFullAcceptReason(forceOverride, totalplayouts, metadataEngine));
    // added for change bestmoves when playouts is not increased
    if (totalplayouts < playouts) isChanged = false;
    setPlayouts(totalplayouts);
    playoutsChanged = true;
    this.estimateArray = compactEstimateArray(estimateArray);
    winrate = moves.get(0).winrate;
    if (moves.get(0).isKataData) {
      scoreMean = moves.get(0).scoreMean;
      scoreStdev = moves.get(0).scoreStdev;
      isKataData = true;
    } else isKataData = false;
    isSaiData = moves.get(0).isSaiData;
    scoreMeanIsBlackPerspective = moves.get(0).scoreMeanIsBlackPerspective;
    engineName = engName;
    komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    if (sourceEngine != null) {
      if (sourceEngine.isDymPda || sourceEngine.pda != 0) {
        pda = sourceEngine.pda;
      } else pda = 0;
    }
    if (!Lizzie.engineGame.current().playingGenmove() && metadataEngine != null) {
      wrn = metadataEngine.wrn;
    }
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
    return true;
  }

  /** Reapplies only candidate visibility after attention changes; analysis values stay intact. */
  public void refreshFocusCandidateVisibility() {
    if (bestMovesOutOfRange != null && !bestMovesOutOfRange.isEmpty()) {
      List<MoveData> complete = new ArrayList<>(bestMoves.size() + bestMovesOutOfRange.size());
      complete.addAll(bestMoves);
      complete.addAll(bestMovesOutOfRange);
      complete.sort(Comparator.comparingInt(move -> move.order));
      bestMoves = complete;
    } else {
      bestMoves = new ArrayList<>(bestMoves);
    }
    bestMovesOutOfRange = new ArrayList<>();
    tryToLimitMoves(bestMoves, List.of(), true);
  }

  private void tryToLimitMoves(List<MoveData> moves, List<MoveData> lastMoves, boolean isMain) {
    // TODO Auto-generated method stub
    List<MoveData> outOfRangeMoves = new ArrayList<>();
    featurecat.lizzie.analysis.TrackingAnalysisController.DisplaySnapshot focus =
        isMain && Lizzie.frame != null ? Lizzie.frame.trackingDisplaySnapshot() : null;
    Set<String> attended = focus != null && Lizzie.frame.isTrackingDisplayCurrent(focus)
        && Lizzie.frame.getDisplayNode().getData() == this ? focus.selectedPoints() : Set.of();
    if (Lizzie.config.limitMaxSuggestion > 0
        && !Lizzie.config.showNoSuggCircle
        && (moves.size() > Lizzie.config.limitMaxSuggestion)) {
      for (int n = Lizzie.config.limitMaxSuggestion; n < moves.size(); n++) {
        MoveData move = moves.get(n);
        boolean needSkip = attended.contains(move.coordinate);
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
    tryToSetBestMoves2FromEngine(
        moves,
        engName,
        isFromLeelaz,
        isFromLeelaz ? Lizzie.leelaz2 : null,
        totalplayouts,
        estimateArray);
  }

  /** Secondary-display counterpart of {@link #tryToSetBestMovesFromEngine}. */
  public void tryToSetBestMoves2FromEngine(
      List<MoveData> moves,
      String engName,
      Leelaz sourceEngine,
      int totalplayouts,
      List<Double> estimateArray) {
    tryToSetBestMoves2FromEngine(
        moves, engName, true, sourceEngine, totalplayouts, estimateArray);
  }

  private void tryToSetBestMoves2FromEngine(
      List<MoveData> moves,
      String engName,
      boolean isFromLeelaz,
      Leelaz metadataEngine,
      int totalplayouts,
      List<Double> estimateArray) {
    tryToSetBestMoves2FromEngine(
        moves, engName, isFromLeelaz, metadataEngine, totalplayouts, estimateArray, false);
  }

  private void tryToSetBestMoves2FromEngine(
      List<MoveData> moves, String engName, boolean isFromLeelaz,
      Leelaz metadataEngine, int totalplayouts, List<Double> estimateArray, boolean forceOverride) {
    if (!forceOverride && Lizzie.config.enableLizzieCache && !Lizzie.config.isAutoAna) {
      if (!(totalplayouts > playouts2
          || isChanged2
          || (metadataEngine != null && pda2 != metadataEngine.pda))) { // ||Lizzie.frame.urlSgf
        if (totalplayouts < playouts2) {
          // Keep the stronger secondary analysis while accepting its restarted ownership stream.
          if (estimateArray == null || estimateArray.isEmpty()) {
            traceAnalysisCacheDecision(
                metadataEngine,
                engName,
                totalplayouts,
                moves,
                playouts2,
                winrate2,
                scoreMean2,
                "REJECT",
                "LOWER_VISITS");
            return;
          }
          traceAnalysisCacheDecision(
              metadataEngine,
              engName,
              totalplayouts,
              moves,
              playouts2,
              winrate2,
              scoreMean2,
              "ACCEPT",
              "OWNERSHIP_BACKFILL");
          this.estimateArray2 = compactEstimateArray(estimateArray);
          return;
        }
        if (estimateArray == null || estimateArray.isEmpty() || this.estimateArray2 != null) {
          traceAnalysisCacheDecision(
              metadataEngine,
              engName,
              totalplayouts,
              moves,
              playouts2,
              winrate2,
              scoreMean2,
              "REJECT",
              "EQUAL_VISITS");
          return;
        }
      }
    }
    traceAnalysisCacheDecision(
        metadataEngine,
        engName,
        totalplayouts,
        moves,
        playouts2,
        winrate2,
        scoreMean2,
        "ACCEPT",
        secondaryFullAcceptReason(totalplayouts, metadataEngine));
    if (totalplayouts < playouts2) isChanged2 = false;
    setPlayouts2(totalplayouts);
    this.estimateArray2 = compactEstimateArray(estimateArray);
    winrate2 = moves.get(0).winrate;
    if (moves.get(0).isKataData) {
      scoreMean2 = moves.get(0).scoreMean;
      scoreStdev2 = moves.get(0).scoreStdev;
      isKataData2 = true;
    } else isKataData2 = false;
    isSaiData2 = moves.get(0).isSaiData;
    scoreMeanIsBlackPerspective2 = moves.get(0).scoreMeanIsBlackPerspective;
    engineName2 = engName;
    if (isFromLeelaz) {
      if (metadataEngine != null && (metadataEngine.isDymPda || metadataEngine.pda != 0)) {
        pda2 = metadataEngine.pda;
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

  private void traceAnalysisCacheDecision(
      Leelaz engineOwner,
      String engName,
      int incomingVisits,
      List<MoveData> moves,
      int cachedVisits,
      double cachedWinrate,
      double cachedScoreLead,
      String decision,
      String reason) {
    try {
      if (!EngineObservation.traceEnabled()) {
        return;
      }
      MoveData incoming = moves == null || moves.isEmpty() ? null : moves.get(0);
      EngineObservation.traceAnalysisCacheDecision(
          engineOwner,
          moveNumber,
          currentBoardRevision(),
          blackToPlay,
          engName,
          incomingVisits,
          incoming == null ? 0.0 : incoming.winrate,
          incoming == null ? 0.0 : incoming.scoreMean,
          cachedVisits,
          cachedWinrate,
          cachedScoreLead,
          decision,
          reason);
    } catch (RuntimeException ignored) {
    }
  }

  private static long currentBoardRevision() {
    Board board = Lizzie.board;
    return board == null ? -1L : board.getContextRevision();
  }

  private String primaryFullAcceptReason(
      boolean forceOverride, int totalplayouts, Leelaz metadataEngine) {
    if (forceOverride) {
      return "FORCE_OVERRIDE";
    }
    if (!Lizzie.config.enableLizzieCache) {
      return "CACHE_DISABLED";
    }
    if (Lizzie.config.isAutoAna) {
      return "AUTO_ANA";
    }
    if (Lizzie.engineGame.current().playing()) {
      return "ENGINE_GAME";
    }
    if (totalplayouts > playouts) {
      return "HIGHER_VISITS";
    }
    if (isChanged) {
      return "IS_CHANGED";
    }
    if (metadataEngine != null && pda != metadataEngine.pda) {
      return "PDA_CHANGED";
    }
    return "OWNERSHIP_FILL";
  }

  private String secondaryFullAcceptReason(int totalplayouts, Leelaz metadataEngine) {
    if (!Lizzie.config.enableLizzieCache) {
      return "CACHE_DISABLED";
    }
    if (Lizzie.config.isAutoAna) {
      return "AUTO_ANA";
    }
    if (totalplayouts > playouts2) {
      return "HIGHER_VISITS";
    }
    if (isChanged2) {
      return "IS_CHANGED";
    }
    if (metadataEngine != null && pda2 != metadataEngine.pda) {
      return "PDA_CHANGED";
    }
    return "OWNERSHIP_FILL";
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
      sb.append(" order ").append(move.order);
      if (move.edgeVisits >= 0) sb.append(" edgeVisits ").append(move.edgeVisits);
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
      sb.append(" order ").append(move.order);
      if (move.edgeVisits >= 0) sb.append(" edgeVisits ").append(move.edgeVisits);
      sb.append(" pv ").append(move.variation.stream().reduce((a, b) -> a + " " + b).get());
      sb.append(" info "); // this order is just because of how the MoveData info parser works
    }
    return sb.toString();
  }

  public void setPlayouts(int playouts) {
    // if (playouts > this.playouts || isChanged) {
    this.playouts = playouts;
    rootVisits = -1;
    analysisSource = null;
    // }
  }

  public void setPlayouts2(int playouts) {
    // if (playouts > this.playouts || isChanged) {
    this.playouts2 = playouts;
    rootVisits2 = -1;
    analysisSource2 = null;
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
    setPlayouts(playouts);
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

  /** True when the primary payload contains a winrate point that can actually be graphed. */
  public boolean hasDisplayablePrimaryAnalysis() {
    if (getPlayouts() <= 0 || !Double.isFinite(winrate) || winrate < 0 || winrate > 100) {
      return false;
    }
    // Imported SGFs may legitimately contain only the serialized analysis header. Live engine
    // results, on the other hand, carry at least one candidate. Requiring either signal prevents
    // download/sync placeholders with a default 50% winrate and a visit counter from suppressing
    // automatic curve completion.
    return analysisHeaderSlots >= 3 || (bestMoves != null && !bestMoves.isEmpty());
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
  }

  /**
   * Returns whether the primary payload is complete enough to be displayed as a whole-game result.
   *
   * <p>Supported Go positions always have pass available, so an empty candidate list is treated as
   * an incomplete engine response rather than as an implicitly terminal position. The analysis JSON
   * and GTP adapters do not expose a reliable terminal-position marker that would justify accepting
   * an empty list.
   */
  public boolean hasCompletePrimaryAnalysis(int targetVisits, boolean ownershipRequested) {
    MoveData firstMove = bestMoves == null || bestMoves.isEmpty() ? null : bestMoves.get(0);
    return getPlayouts() >= Math.max(1, targetVisits)
        && firstMove != null
        && firstMove.coordinate != null
        && !firstMove.coordinate.trim().isEmpty()
        && firstMove.playouts > 0
        && Double.isFinite(firstMove.winrate)
        && firstMove.variation != null
        && !firstMove.variation.isEmpty()
        && firstMove.variation.get(0) != null
        && !firstMove.variation.get(0).trim().isEmpty()
        && (!ownershipRequested || (estimateArray != null && !estimateArray.isEmpty()));
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
    scoreMeanIsBlackPerspective = false;
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
    scoreMeanIsBlackPerspective2 = false;
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
    this.rootVisits = data.rootVisits;
    this.rootVisits2 = data.rootVisits2;
    // Copies preserve saved analysis, not permission to refresh it from a live stream.
    this.analysisSource = null;
    this.analysisSource2 = null;
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
    this.scoreMeanIsBlackPerspective = data.scoreMeanIsBlackPerspective;
    this.scoreMeanIsBlackPerspective2 = data.scoreMeanIsBlackPerspective2;
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
