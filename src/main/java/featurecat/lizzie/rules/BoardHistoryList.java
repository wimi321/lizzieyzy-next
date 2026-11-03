package featurecat.lizzie.rules;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Linked list data structure to store board history */
public class BoardHistoryList {
  public enum SessionRulesKind {
    UNSPECIFIED,
    VALID,
    INVALID
  }

  public enum SessionRulesSource {
    NONE,
    EXTERNAL_IMPORT,
    MANUAL_SELECTION
  }

  /** Immutable rules target adopted by this history and its revision. */
  public static final class SessionRulesTarget {
    private final SessionRulesKind kind;
    private final String rawDeclaration;
    private final KataGoRules parsedRules;
    private final SessionRulesSource source;
    private final long revision;

    private SessionRulesTarget(
        SessionRulesKind kind,
        String rawDeclaration,
        KataGoRules parsedRules,
        SessionRulesSource source,
        long revision) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.rawDeclaration = rawDeclaration;
      this.parsedRules = parsedRules;
      this.source = Objects.requireNonNull(source, "source");
      this.revision = revision;
    }

    public SessionRulesKind kind() {
      return kind;
    }

    public String rawDeclaration() {
      return rawDeclaration;
    }

    public Optional<KataGoRules> parsedRules() {
      return Optional.ofNullable(parsedRules);
    }

    public SessionRulesSource source() {
      return source;
    }

    public long revision() {
      return revision;
    }
  }

  /** Opaque claim for one manual rules selection against this history. */
  public static final class ManualRulesIntent {
    private final SessionRulesTarget previousTarget;
    private final long generation;

    private ManualRulesIntent(SessionRulesTarget previousTarget, long generation) {
      this.previousTarget = previousTarget;
      this.generation = generation;
    }

    public SessionRulesTarget previousTarget() {
      return previousTarget;
    }
  }

  private GameInfo gameInfo;
  private BoardHistoryNode head;
  private SessionRulesTarget sessionRulesTarget;
  private SessionRulesTarget analysisOverrideTarget;
  private Object analysisOverridePrimary;
  private Object analysisOverrideMirror;
  private long analysisOverridePrimaryGeneration = -1L;
  private long manualRulesIntentGeneration;

  /**
   * Initialize a new board history list, whose first node is data
   *
   * @param data the data to be stored for the first entry
   */
  public BoardHistoryList(BoardData data) {
    head = new BoardHistoryNode(data);
    gameInfo = new GameInfo();
    sessionRulesTarget =
        new SessionRulesTarget(
            SessionRulesKind.UNSPECIFIED, null, null, SessionRulesSource.NONE, 0L);
  }

  /** Atomically returns the immutable rules target currently adopted by this history. */
  public synchronized SessionRulesTarget captureSessionRules() {
    return sessionRulesTarget;
  }

  public synchronized ManualRulesIntent beginManualRulesIntent() {
    return new ManualRulesIntent(sessionRulesTarget, ++manualRulesIntentGeneration);
  }

  public synchronized boolean isLatestManualRulesIntent(ManualRulesIntent intent) {
    return intent != null && intent.generation == manualRulesIntentGeneration;
  }

  /** Rebinds an internally restored history to the exact pre-restore session target. */
  public synchronized void restoreSessionRulesTarget(SessionRulesTarget target) {
    sessionRulesTarget = Objects.requireNonNull(target, "target");
    manualRulesIntentGeneration++;
    clearAnalysisOverride();
  }

  public synchronized void authorizeAnalysisWithCurrentRules(
      SessionRulesTarget target, Object primary, long primaryGeneration, Object mirror) {
    if (target == null
        || target != sessionRulesTarget
        || primary == null
        || primaryGeneration < 0L) {
      throw new IllegalArgumentException("Analysis override does not match current session rules");
    }
    analysisOverrideTarget = target;
    analysisOverridePrimary = primary;
    analysisOverridePrimaryGeneration = primaryGeneration;
    analysisOverrideMirror = mirror;
  }

  public synchronized boolean permitsAnalysisWithCurrentRules(
      SessionRulesTarget target, Object primary, long primaryGeneration, Object mirror) {
    return target != null
        && target == sessionRulesTarget
        && analysisOverrideTarget == target
        && analysisOverridePrimary == primary
        && analysisOverridePrimaryGeneration == primaryGeneration
        && analysisOverrideMirror == mirror;
  }

  /** Retires any explicit continue decision for the captured target without changing the target. */
  public synchronized void revokeAnalysisOverride(SessionRulesTarget target) {
    if (target != null && target == sessionRulesTarget && analysisOverrideTarget == target) {
      clearAnalysisOverride();
    }
  }

  /**
   * Publishes the root SGF RU declaration as this history's external target.
   *
   * <p>A null declaration means RU is missing and is distinct from an invalid or blank declaration.
   * Every invocation publishes a newer revision, including missing and invalid declarations.
   */
  public synchronized SessionRulesTarget publishExternalRules(String rawDeclaration) {
    manualRulesIntentGeneration++;
    clearAnalysisOverride();
    long revision = sessionRulesTarget.revision() + 1L;
    if (rawDeclaration == null) {
      sessionRulesTarget =
          new SessionRulesTarget(
              SessionRulesKind.UNSPECIFIED,
              null,
              null,
              SessionRulesSource.EXTERNAL_IMPORT,
              revision);
    } else {
      Optional<KataGoRules> parsed = KataGoRules.parse(rawDeclaration);
      if (parsed.isPresent() && parsed.get().hasRequiredFields()) {
        sessionRulesTarget =
            new SessionRulesTarget(
                SessionRulesKind.VALID,
                rawDeclaration,
                parsed.get(),
                SessionRulesSource.EXTERNAL_IMPORT,
                revision);
      } else {
        sessionRulesTarget =
            new SessionRulesTarget(
                SessionRulesKind.INVALID,
                rawDeclaration,
                null,
                SessionRulesSource.EXTERNAL_IMPORT,
                revision);
      }
    }
    return sessionRulesTarget;
  }

  /** Publishes a successful manual selection as a valid target at a newer revision. */
  public synchronized SessionRulesTarget publishManualRules(KataGoRules rules) {
    manualRulesIntentGeneration++;
    return publishManualRulesUnchecked(rules);
  }

  /** Publishes a manual selection only while its history-owned intent is still latest. */
  public synchronized Optional<SessionRulesTarget> publishManualRulesIfCurrent(
      ManualRulesIntent intent, KataGoRules rules) {
    if (intent == null
        || intent.generation != manualRulesIntentGeneration
        || sessionRulesTarget != intent.previousTarget) {
      return Optional.empty();
    }
    return Optional.of(publishManualRulesUnchecked(rules));
  }

  private SessionRulesTarget publishManualRulesUnchecked(KataGoRules rules) {
    clearAnalysisOverride();
    Objects.requireNonNull(rules, "rules");
    if (!rules.hasRequiredFields()) {
      throw new IllegalArgumentException("Manual session rules must contain all required fields");
    }
    sessionRulesTarget =
        new SessionRulesTarget(
            SessionRulesKind.VALID,
            null,
            rules,
            SessionRulesSource.MANUAL_SELECTION,
            sessionRulesTarget.revision() + 1L);
    return sessionRulesTarget;
  }

  private void clearAnalysisOverride() {
    analysisOverrideTarget = null;
    analysisOverridePrimary = null;
    analysisOverridePrimaryGeneration = -1L;
    analysisOverrideMirror = null;
  }

  public GameInfo getGameInfo() {
    return gameInfo;
  }

  public void setGameInfo(GameInfo gameInfo) {
    this.gameInfo = gameInfo;
  }

  public void addNodeProperty(String key, String value) {
    synchronized (this) {
      this.getData().addProperty(key, value);
      if ("MN".equals(key)) {
        moveNumber(Integer.parseInt(value));
      }
    }
  }

  public void moveNumber(int moveNumber) {
    synchronized (this) {
      BoardData data = this.getData();
      if (data.isMoveNode()) {
        int[] moveNumberList = this.getMoveNumberList();
        moveNumberList[Board.getIndex(data.lastMove.get()[0], data.lastMove.get()[1])] = moveNumber;
        Optional<BoardHistoryNode> node = this.getCurrentHistoryNode().previous();
        while (node.isPresent() && node.get().numberOfChildren() <= 1) {
          BoardData nodeData = node.get().getData();
          if (nodeData.isMoveNode() && nodeData.moveNumber >= moveNumber) {
            moveNumber = (moveNumber > 1) ? moveNumber - 1 : 0;
            moveNumberList[Board.getIndex(nodeData.lastMove.get()[0], nodeData.lastMove.get()[1])] =
                moveNumber;
          }
          node = node.get().previous();
        }
      }
    }
  }

  private boolean rejectFoxNavigation() {
    return Lizzie.board != null
        && Lizzie.board.getHistory() == this
        && featurecat.lizzie.fox.FoxLiveSession.rejectHistoryNavigation();
  }

  public void toBranchTop() {
    if (rejectFoxNavigation()) return;
    BoardHistoryNode start = head;
    while (start.previous().isPresent()) {
      BoardHistoryNode pre = start.previous().get();
      if (pre.next(true).isPresent() && pre.next(true).get() != start) {
        previous();
        break;
      }
      previous();
      start = pre;
    }
  }

  public synchronized BoardHistoryList shallowCopy() {
    BoardHistoryList copy = new BoardHistoryList(null);
    copy.head = head;
    copy.gameInfo = gameInfo;
    copy.sessionRulesTarget = sessionRulesTarget;
    copy.analysisOverrideTarget = analysisOverrideTarget;
    copy.analysisOverridePrimary = analysisOverridePrimary;
    copy.analysisOverrideMirror = analysisOverrideMirror;
    copy.analysisOverridePrimaryGeneration = analysisOverridePrimaryGeneration;
    return copy;
  }

  /** Clear history. */
  public void clear() {
    head.clear();
  }

  /**
   * Add new data after head. Overwrites any data that may have been stored after head.
   *
   * @param data the data to add
   */
  public void add(BoardData data) {
    head = head.add(new BoardHistoryNode(data));
  }

  public void addOrGoto(BoardData data) {
    addOrGoto(data, false);
  }

  public void addOrGoto(BoardData data, boolean newBranch) {
    head = head.addOrGoto(data, newBranch);
  }

  public void addOrGoto(BoardData data, boolean newBranch, boolean changeMove) {
    head = head.addOrGoto(data, newBranch, changeMove, false);
  }

  public void addOrGoto(BoardData data, boolean newBranch, boolean changeMove, boolean tsumego) {
    head = head.addOrGoto(data, newBranch, changeMove, tsumego);
  }

  Optional<BoardData> nextByMoveIdentity(BoardData data) {
    Optional<BoardHistoryNode> n = head.findDirectChildByMoveIdentity(data);
    if (n.isPresent()) {
      if (head.hasRemovedStone()) head.clearAndSyncBoard(true);
      if (Lizzie.leelaz != null) Lizzie.leelaz.clearBestMoves();
      head = n.get();
      head.placeExtraStones();
    }
    return n.map(BoardHistoryNode::getData);
  }

  /**
   * moves the pointer to the left, returns the data stored there
   *
   * @return data of previous node, Optional.empty if there is no previous node
   */
  public Optional<BoardData> previous() {
    if (rejectFoxNavigation()) return Optional.empty();
    if (!head.previous().isPresent()) return Optional.empty();
    else setHead(head.previous().get());
    return Optional.of(head.getData());
  }

  public void toStart() {
    while (previous().isPresent())
      ;
  }

  /**
   * moves the pointer to the right, returns the data stored there
   *
   * @return the data of next node, Optional.empty if there is no next node
   */
  public Optional<BoardData> next() {
    return next(false);
  }

  public Optional<BoardData> next(boolean includeDummay) {
    if (rejectFoxNavigation()) return Optional.empty();
    Optional<BoardHistoryNode> n = head.next(includeDummay);
    // n.ifPresent(x -> head = x);
    if (n.isPresent()) {
      if (!featurecat.lizzie.fox.FoxLiveSession.isActive() && Lizzie.leelaz != null)
        Lizzie.leelaz.clearBestMoves();
      setHead(n.get());
      // Lizzie.board.clearAfterMove();
    }
    return n.map(x -> x.getData());
  }

  /**
   * Moves the pointer to the variation number idx, returns the data stored there.
   *
   * @return the data of next node, Optional.empty if there is no variation with index.
   */
  public Optional<BoardData> nextVariation(int idx) {
    if (rejectFoxNavigation()) return Optional.empty();
    if (featurecat.lizzie.fox.FoxLiveSession.isActive()) return nextVariationWithoutEngineSync(idx);
    Optional<BoardHistoryNode> n = head.getVariation(idx);
    if (n.isPresent()) {
      if (head.hasRemovedStone()) head.clearAndSyncBoard(true);
      setHead(n.get());
      head.placeExtraStones();
    }
    return n.map(x -> x.getData());
  }

  Optional<BoardData> nextVariationWithoutEngineSync(int idx) {
    if (rejectFoxNavigation()) return Optional.empty();
    Optional<BoardHistoryNode> next = head.getVariation(idx);
    next.ifPresent(this::setHead);
    return next.map(BoardHistoryNode::getData);
  }

  /**
   * Does not change the pointer position
   *
   * @return the data stored at the next index, if any, Optional.empty otherwise.
   */
  public Optional<BoardData> getNext() {
    return getNext(false);
  }

  public Optional<BoardData> getNext(boolean includeDummy) {
    return head.next(includeDummy).map(x -> x.getData());
  }

  /**
   * @return nexts for display
   */
  public List<BoardHistoryNode> getNexts() {
    return head.getVariations();
  }

  /**
   * Does not change the pointer position.
   *
   * @return the data stored at the previous index, if any, Optional.empty otherwise.
   */
  public Optional<BoardData> getPrevious() {
    return head.previous().map(p -> p.getData());
  }

  /**
   * @return the data of the current node
   */
  public BoardData getData() {
    return head.getData();
  }

  public void setStone(int[] coordinates, Stone stone) {
    if (!Board.isValid(coordinates[0], coordinates[1])) return;
    int index = Board.getIndex(coordinates[0], coordinates[1]);
    head.getData().stones[index] = stone;
    head.getData().zobrist.toggleStone(coordinates[0], coordinates[1], stone);
  }

  public Stone[] getStones() {
    return head.getData().stones;
  }

  public Optional<int[]> getLastMove() {
    return head.getData().lastMove;
  }

  public Optional<int[]> getNextMove() {
    return getNext().flatMap(n -> n.lastMove);
  }

  public Stone getLastMoveColor() {
    return head.getData().lastMoveColor;
  }

  public boolean isBlacksTurn() {
    return head.getData().blackToPlay;
  }

  public Zobrist getZobrist() {
    return head.getData().zobrist.clone();
  }

  public int getMoveNumber() {
    return head.getData().moveNumber;
  }

  public int getMoveMNNumber() {
    return head.getData().moveMNNumber;
  }

  public int[] getMoveNumberList() {
    return head.getData().moveNumberList;
  }

  public BoardHistoryNode getCurrentHistoryNode() {
    return head;
  }

  public void setHead(BoardHistoryNode node) {
    if (rejectFoxNavigation()) return;
    if (node == null) return;
    if (featurecat.lizzie.fox.FoxLiveSession.isActive()
        && Lizzie.board != null
        && Lizzie.board.getHistory() == this) {
      BoardHistoryNode targetRoot = node;
      while (targetRoot.previous().isPresent()) targetRoot = targetRoot.previous().get();
      if (targetRoot != root()) return;
    }
    head = node;
    featurecat.lizzie.fox.FoxLiveSession.historyCursorChanged(this);
  }

  public boolean isEmptyBoard() {
    if (!head.previous().isPresent() && !head.next().isPresent()) return true;
    else return false;
  }

  public boolean noStoneBoard() {
    if (head.previous().isPresent() || Lizzie.board.hasStartStone) {
      return false;
    }
    for (Stone stone : head.getData().stones) {
      if (!stone.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  //  public BoardHistoryNode getRecentAnalyzedNode() {
  //    BoardHistoryNode node = head;
  //    if (!node.getData().bestMoves.isEmpty()) return node;
  //    while (node.next().isPresent()) {
  //      if (!node.getData().bestMoves.isEmpty()) return node;
  //      else node = node.next().get();
  //    }
  //    node = head;
  //    while (node.previous().isPresent()) {
  //      if (!node.getData().bestMoves.isEmpty()) return node;
  //      else node = node.previous().get();
  //    }
  //    return head;
  //  }
  //
  public BoardHistoryNode getFirstAnalyzedNode2() {
    BoardHistoryNode node = head;
    if (!node.getData().bestMoves2.isEmpty()) return node;
    while (node.next().isPresent()) {
      if (!node.getData().bestMoves2.isEmpty()) return node;
      else node = node.next().get();
    }
    node = head;
    while (node.previous().isPresent()) {
      if (!node.getData().bestMoves2.isEmpty()) return node;
      else node = node.previous().get();
    }
    return head;
  }

  /**
   * @param data the board position to check against superko
   * @return whether or not the given position violates the superko rule at the head's state
   */
  public boolean violatesSuperko(BoardData data) {
    BoardHistoryNode head = this.head;

    // check to see if this position has occurred before
    while (head.previous().isPresent()) {
      // if two zobrist hashes are equal, and it's the same player to coordinate, they
      // are the same
      // position
      if (data.zobrist.equals(head.getData().zobrist)
          && data.blackToPlay == head.getData().blackToPlay) return true;

      head = head.previous().get();
    }

    // no position matched this position, so it's valid
    return false;
  }

  public boolean violatesKoRule(BoardData data) {
    // check if the current position is identical to the position two moves ago
    return this.head
        .previous()
        .map(p -> p != null && data.zobrist.equals(p.getData().zobrist))
        .orElse(false);
  }

  /**
   * Returns the root node
   *
   * @return root node
   */
  public BoardHistoryNode root() {
    BoardHistoryNode top = head;
    while (top.previous().isPresent()) {
      top = top.previous().get();
    }
    return top;
  }

  /**
   * Returns the length of current branch
   *
   * @return length of current branch
   */
  public int currentBranchLength() {
    return getMoveNumber() + head.getDepth();
  }

  /**
   * Returns the length of main trunk
   *
   * @return length of main trunk
   */
  public int mainTrunkLength() {
    return root().getDepth();
  }

  public BoardHistoryNode getStart() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    return e;
  }

  public BoardHistoryNode getEnd() {
    BoardHistoryNode e = head;
    while (e.next().isPresent()) {
      e = e.next().get();
    }
    return e;
  }

  public BoardHistoryNode getMainEnd() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    while (e.next().isPresent()) {
      e = e.next().get();
    }
    return e;
  }

  public BoardHistoryNode getCurOrMainEnd(boolean editMode) {
    if (editMode) return getCurrentHistoryNode();
    else return getMainEnd();
  }

  public BoardHistoryNode getMainEndWithPass() {
    BoardHistoryNode e = head;
    while (e.previous().isPresent()) {
      e = e.previous().get();
    }
    while (e.next(true).isPresent()) {
      e = e.next(true).get();
    }
    return e;
  }

  public void pass(Stone color) {
    pass(color, false, false, false);
  }

  public void pass(Stone color, boolean newBranch) {
    pass(color, newBranch, false, false);
  }

  public void pass(Stone color, boolean newBranch, boolean dummy) {
    pass(color, newBranch, dummy, false);
  }

  public void pass(Stone color, boolean newBranch, boolean dummy, boolean changeMove) {
    pass(color, newBranch, dummy, changeMove, false);
  }

  public void pass(
      Stone color, boolean newBranch, boolean dummy, boolean changeMove, boolean addLast) {
    pass(color, newBranch, dummy, changeMove, addLast, false, false);
  }

  void passForEngineGame(Stone color, boolean newMoveNumberInBranch) {
    pass(color, false, false, false, false, true, newMoveNumberInBranch);
  }

  private void pass(
      Stone color,
      boolean newBranch,
      boolean dummy,
      boolean changeMove,
      boolean addLast,
      boolean engineGamePure,
      boolean frozenNewMoveNumberInBranch) {
    synchronized (this) {

      // check to see if this move is being replayed in history
      if (!engineGamePure
          && !addLast
          && this.getNext().map(BoardHistoryList::isReplayablePass).orElse(false)
          && !newBranch) {
        // this is the next move in history. Just increment history so that we don't
        // erase the
        // redo's
        this.next();
        return;
      }
      BoardHistoryNode curNode = this.head;
      if (addLast) curNode = head.getLast();

      Stone[] stones = curNode.getData().stones.clone();
      Zobrist zobrist = curNode.getData().zobrist.clone();

      int moveNumber = curNode.getData().moveNumber + 1;

      int[] moveNumberList =
          newBranch && curNode.next(true).map(s -> s.getData()).isPresent()
              ? new int[Board.boardWidth * Board.boardHeight]
              : curNode.getData().moveNumberList.clone();
      // build the new game state
      BoardData newState =
          BoardData.pass(
              stones,
              color,
              color.equals(Stone.WHITE),
              zobrist,
              moveNumber,
              moveNumberList,
              curNode.getData().blackCaptures,
              curNode.getData().whiteCaptures,
              0,
              0);
      newState.dummy = dummy;
      newState.moveMNNumber = curNode.getData().nextMoveMNNumber(newBranch);

      // update history with pass
      if (addLast) {
        head.getLast().addAtLast(newState);
      } else if (engineGamePure) {
        this.head = this.head.addOrGotoForEngineGame(newState, frozenNewMoveNumberInBranch);
      } else {
        this.addOrGoto(newState, newBranch);
      }
    }
  }

  private static boolean isReplayablePass(BoardData data) {
    return data != null && data.isPassNode() && !data.dummy;
  }

  public void place(int x, int y, Stone color) {
    place(x, y, color, false);
  }

  public void place(int x, int y, Stone color, boolean newBranch) {
    place(x, y, color, newBranch, false, false);
  }

  public void place(int x, int y, Stone color, boolean newBranch, boolean changeMove) {
    place(x, y, color, newBranch, changeMove, false);
  }

  public void place(
      int x, int y, Stone color, boolean newBranch, boolean changeMove, boolean addLast) {
    place(
        x,
        y,
        color,
        newBranch,
        changeMove,
        addLast,
        Lizzie.config.noCapture,
        Lizzie.leelaz != null && Lizzie.leelaz.canSuicidal);
  }

  /** Places a move using rules frozen by an owning engine-game transaction. */
  public void place(
      int x,
      int y,
      Stone color,
      boolean newBranch,
      boolean changeMove,
      boolean addLast,
      boolean noCapture,
      boolean canSuicidal) {
    place(x, y, color, newBranch, changeMove, addLast, noCapture, canSuicidal, false, false);
  }

  void placeForEngineGame(
      int x,
      int y,
      Stone color,
      boolean noCapture,
      boolean canSuicidal,
      boolean newMoveNumberInBranch) {
    place(x, y, color, false, false, false, noCapture, canSuicidal, true, newMoveNumberInBranch);
  }

  private void place(
      int x,
      int y,
      Stone color,
      boolean newBranch,
      boolean changeMove,
      boolean addLast,
      boolean noCapture,
      boolean canSuicidal,
      boolean engineGamePure,
      boolean frozenNewMoveNumberInBranch) {
    synchronized (this) {
      BoardHistoryNode curNode = this.head;
      if (addLast) curNode = head.getLast();
      if (!Board.isValid(x, y)
          || (curNode.getData().stones[Board.getIndex(x, y)] != Stone.EMPTY && !newBranch)) return;
      // Lizzie.board.modifyStart();
      double nextWinrate = -100;
      if (curNode.getData().winrate >= 0) nextWinrate = 100 - curNode.getData().winrate;

      // check to see if this coordinate is being replayed in history
      if (!engineGamePure && !addLast) {
        Optional<int[]> nextLast = this.getNext().flatMap(n -> n.lastMove);
        if (nextLast.isPresent()
            && nextLast.get()[0] == x
            && nextLast.get()[1] == y
            && !newBranch
            && !changeMove) {
          // this is the next coordinate in history. Just increment history so that we
          // don't erase the
          // redo's
          this.next();
          //   Lizzie.board.modifyEnd();
          return;
        }
      }
      // load a copy of the data at the current node of history
      Stone[] stones = curNode.getData().stones.clone();
      Zobrist zobrist = curNode.getData().zobrist.clone();
      Optional<int[]> lastMove = Optional.of(new int[] {x, y});
      int moveNumber = curNode.getData().moveNumber + 1;
      int moveMNNumber = curNode.getData().nextMoveMNNumber(newBranch);
      int[] moveNumberList =
          newBranch && curNode.next(true).map(s -> s.getData()).isPresent()
              ? new int[Board.boardWidth * Board.boardHeight]
              : curNode.getData().moveNumberList.clone();

      moveNumberList[Board.getIndex(x, y)] = moveMNNumber > -1 ? moveMNNumber : moveNumber;

      // set the stone at (x, y) to color
      stones[Board.getIndex(x, y)] = color;
      zobrist.toggleStone(x, y, color);

      // remove enemy stones
      int capturedStones = 0;
      int isSuicidal = 0;
      if (!noCapture) {
        capturedStones += Board.removeDeadChain(x + 1, y, color.opposite(), stones, zobrist);
        capturedStones += Board.removeDeadChain(x, y + 1, color.opposite(), stones, zobrist);
        capturedStones += Board.removeDeadChain(x - 1, y, color.opposite(), stones, zobrist);
        capturedStones += Board.removeDeadChain(x, y - 1, color.opposite(), stones, zobrist);

        // check to see if the player made a suicidal coordinate
        isSuicidal = Board.removeDeadChain(x, y, color, stones, zobrist);
      }
      for (int i = 0; i < Board.boardWidth * Board.boardHeight; i++) {
        if (stones[i].equals(Stone.EMPTY)) {
          moveNumberList[i] = 0;
        }
      }

      int bc = curNode.getData().blackCaptures;
      int wc = curNode.getData().whiteCaptures;
      if (color.isBlack()) bc += capturedStones;
      else wc += capturedStones;
      BoardData newState =
          BoardData.move(
              stones,
              lastMove.get(),
              color,
              color.equals(Stone.WHITE),
              zobrist,
              moveNumber,
              moveNumberList,
              bc,
              wc,
              nextWinrate,
              0);
      newState.moveMNNumber = moveMNNumber;

      // don't make this coordinate if it is suicidal or violates superko
      boolean violatesKoRule =
          curNode
              .previous()
              .map(p -> p != null && newState.zobrist.equals(p.getData().zobrist))
              .orElse(false);
      if (violatesKoRule) {
        //    Lizzie.board.modifyEnd();
        return;
      }
      if (canSuicidal) {
        if (isSuicidal == 1) {
          //  Lizzie.board.modifyEnd();
          return;
        }
      } else if (isSuicidal > 0) {
        //    Lizzie.board.modifyEnd();
        return;
      }

      // update history with this coordinate
      if (addLast) {
        head.getLast().addAtLast(newState);
      } else if (engineGamePure) {
        this.head = this.head.addOrGotoForEngineGame(newState, frozenNewMoveNumberInBranch);
      } else {
        this.addOrGoto(newState, newBranch, changeMove);
      }
      //   Lizzie.board.modifyEnd();
      // Lizzie.frame.renderVarTree(0, 0, false, false);
    }
  }

  //  public void addStone(int x, int y, Stone color) {
  //    synchronized (this) {
  //      if (!Board.isValid(x, y) || this.getStones()[Board.getIndex(x, y)] != Stone.EMPTY) return;
  //
  //      Stone[] stones = this.getData().stones;
  //      Zobrist zobrist = this.getData().zobrist;
  //
  //      // set the stone at (x, y) to color
  //      stones[Board.getIndex(x, y)] = color;
  //      zobrist.toggleStone(x, y, color);
  //    }
  //  }

  public void addExtraStone(int x, int y, Stone color) {
    synchronized (this) {
      if (!Board.isValid(x, y) || this.getStones()[Board.getIndex(x, y)] != Stone.EMPTY) return;

      Stone[] stones = this.getData().stones;
      Zobrist zobrist = this.getData().zobrist;

      // set the stone at (x, y) to color
      stones[Board.getIndex(x, y)] = color;
      zobrist.toggleStone(x, y, color);
      head.addExtraStones(x, y, color.isBlack());
    }
  }

  public void removeStone(int x, int y, Stone color) {
    synchronized (this) {
      if (!Board.isValid(x, y) || this.getStones()[Board.getIndex(x, y)] == Stone.EMPTY) return;

      BoardData data = this.getData();
      Stone[] stones = data.stones;
      Zobrist zobrist = data.zobrist;

      // set the stone at (x, y) to empty
      Stone oriColor = stones[Board.getIndex(x, y)];
      stones[Board.getIndex(x, y)] = Stone.EMPTY;
      zobrist.toggleStone(x, y, oriColor);
      data.moveNumberList[Board.getIndex(x, y)] = 0;
      head.setRemovedStone();

      Lizzie.frame.refresh();
    }
  }

  public void flatten() {
    Stone[] stones = this.getStones();
    boolean blackToPlay = this.isBlacksTurn();
    Zobrist zobrist = this.getZobrist().clone();
    BoardHistoryList oldHistory = this;

    head =
        new BoardHistoryNode(
            BoardData.snapshot(
                stones,
                Optional.empty(),
                Stone.EMPTY,
                blackToPlay,
                zobrist,
                0,
                new int[Board.boardWidth * Board.boardHeight],
                0,
                0,
                0.0,
                0));
    this.setGameInfo(oldHistory.getGameInfo());
  }

  public boolean goToMoveNumber(int moveNumber, boolean withinBranch) {
    int delta = moveNumber - this.getMoveNumber();
    boolean moved = false;
    for (int i = 0; i < Math.abs(delta); i++) {
      if (withinBranch && delta < 0) {
        BoardHistoryNode currentNode = this.getCurrentHistoryNode();
        if (!currentNode.isFirstChild()) {
          break;
        }
      }
      if (!(delta > 0 ? next().isPresent() : previous().isPresent())) {
        break;
      }
      moved = true;
    }
    return moved;
  }

  public int sync(BoardHistoryList newList) {
    int diffMoveNo = -1;

    BoardHistoryNode node = this.getCurrentHistoryNode();
    BoardHistoryNode prev = node.previous().map(p -> p).orElse(null);
    // From begin
    while (prev != null) {
      node = prev;
      prev = node.previous().map(p -> p).orElse(null);
    }
    // Compare
    BoardHistoryNode newNode = newList.getCurrentHistoryNode();
    BoardHistoryNode newPrev = newNode.previous().map(p -> p).orElse(null);
    while (newPrev != null) {
      newNode = newPrev;
      newPrev = newNode.previous().map(p -> p).orElse(null);
    }

    while (newNode != null) {
      if (node == null) {
        // Add
        prev.addOrGoto(newNode.getData().clone());
        node = prev.next().map(n -> n).orElse(null);
        if (diffMoveNo < 0) {
          diffMoveNo = newNode.getData().moveNumber;
        }
        node.sync(newNode);
        break;
      } else {
        if (!node.compare(newNode)) {
          if (diffMoveNo < 0) {
            diffMoveNo = newNode.getData().moveNumber;
          }
          node.sync(newNode);
          break;
        }
        node.syncVariations(newNode);
      }
      prev = node;
      node = node.next(true).map(n -> n).orElse(null);
      newNode = newNode.next(true).map(n -> n).orElse(null);
    }

    return diffMoveNo;
  }

  public String getCurrentTurnPlayerShortName() {
    // TODO Auto-generated method stub
    String name;
    if (isBlacksTurn()) {
      name = getGameInfo().getPlayerBlack();
    } else {
      name = getGameInfo().getPlayerWhite();
    }
    if (name.length() > 21) return name.substring(0, 20);
    else return name;
  }

  public void setRemovedStone() {
    head.setRemovedStone();
  }
}
