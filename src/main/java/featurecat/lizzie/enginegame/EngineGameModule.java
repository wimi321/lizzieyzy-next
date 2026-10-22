package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineGameParameterSession;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Owns product batch admission, GamePlan, product transaction identity, BatchState statistics,
 * successor planning after exact retirement, history records, and the public snapshot.
 */
public final class EngineGameModule implements EngineGameControl, EngineGameState {
  private final Object lock = new Object();
  private EngineGameSnapshot snapshot = new EngineGameSnapshot.Idle();
  private EngineGameBatchState batch;
  private EngineGamePlan plan;
  private EngineGameTransaction transaction;
  private StartObserver observer;
  private boolean observerCompleted;
  private boolean pendingSuccessor;
  private BatchSummary lastSummary;
  private EngineGameCompletionFacts lastCompletion;
  private EngineGameChrome chrome = SwingEngineGameChrome.INSTANCE;
  private MatchRulesSnapshot matchRulesSnapshot;
  private EngineGameKomiState komiState;
  private String parameterRestoreFailure;
  private MatchRulesAdmission.ConsentKey grantedMatchRulesConsent;
  private MatchRulesConsent matchRulesConsent =
      featurecat.lizzie.gui.SwingMatchRulesConsent.INSTANCE;

  @Override
  public Acceptance accept(EngineGameBatchSpec spec, StartObserver observer) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(observer, "observer");
    EngineManager manager = Lizzie.engineManager;
    Rejection rejection = validateSpec(spec);
    if (rejection != null) {
      presentRejection(rejection);
      return new Acceptance.Rejected(rejection);
    }
    if (manager == null) {
      return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
    }
    int firstIndex = manager.resolveEngineGameParticipant(spec.first());
    int secondIndex = manager.resolveEngineGameParticipant(spec.second());
    if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) {
      presentRejection(new Rejection.InvalidParticipantCombination());
      return new Acceptance.Rejected(new Rejection.InvalidParticipantCombination());
    }
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
    }
    EngineGameBatchState acceptedBatch = new EngineGameBatchState(spec, firstIndex, secondIndex);
    EngineGamePlan acceptedPlan = createPlan(acceptedBatch);
    EngineGameTransaction acceptedTransaction = new EngineGameTransaction(acceptedPlan);
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
      this.batch = acceptedBatch;
      this.plan = acceptedPlan;
      this.transaction = acceptedTransaction;
      this.observer = observer;
      this.observerCompleted = false;
      this.pendingSuccessor = false;
      this.lastCompletion = null;
      this.komiState = null;
      matchRulesSnapshot =
          MatchRulesSnapshot.preparing(
              acceptedPlan.matchRules(), acceptedPlan.black(), acceptedPlan.white());
      publishLocked(
          new EngineGameSnapshot.BatchActive(
              acceptedBatch.summary(), new GameActivity.Starting(acceptedPlan.view())));
    }
    ensureOutputIdentity(manager, acceptedPlan);
    boolean started = manager.startEngineGame(acceptedPlan);
    if (!started) {
      synchronized (lock) {
        this.observer = null;
        observerCompleted = false;
        clearAcceptedLocked();
        publishLocked(new EngineGameSnapshot.Idle());
        // Keep a failed snapshot only after a started prepare, not a refused owner start.
      }
      return new Acceptance.Rejected(ownerRejection(manager, acceptedPlan));
    }
    publishChrome(EngineGameChromeTransition.Kind.STARTING);
    return new Acceptance.Accepted();
  }

  @Override
  public void stop() {
    StartObserver pending = null;
    boolean starting;
    boolean betweenGames;
    EngineManager manager;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.Idle) {
        return;
      }
      starting =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.Starting;
      betweenGames =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.BetweenGames;
      if (starting && observer != null && !observerCompleted) {
        pending = observer;
        observerCompleted = true;
        observer = null;
      }
      pendingSuccessor = false;
      komiState = null;
      if (batch != null && batch.completedGames() > 0) {
        plan = null;
        transaction = null;
      } else {
        clearAcceptedLocked();
      }
      publishLocked(new EngineGameSnapshot.Idle());
      manager = Lizzie.engineManager;
    }
    if (pending != null) {
      pending.startFailed(new StartFailure.CancelledByUser());
    }
    publishChrome(EngineGameChromeTransition.Kind.USER_STOPPED);
    if (betweenGames) {
      return;
    }
    if (manager != null) {
      if (starting) {
        manager.clearEngineGame();
      } else {
        manager.stopEngineGame(-1, true);
      }
    }
  }

  @Override
  public void pause() {
    EngineGameTransaction pausedTransaction = null;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing playing
          && playing.runState() == RunState.RUNNING) {
        if (transaction != null) {
          transaction.setPaused(true);
        }
        publishLocked(
            new EngineGameSnapshot.BatchActive(
                active.batch(), new GameActivity.Playing(playing.view(), RunState.PAUSED)));
        pausedTransaction = transaction;
      }
    }
    if (pausedTransaction != null) {
      publishChrome(EngineGameChromeTransition.Kind.PAUSED);
      EngineManager.pauseEngineGame(ownerOf(pausedTransaction));
    }
  }

  @Override
  public void resume() {
    EngineGameTransaction resumedTransaction = null;
    boolean blocked = false;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing playing
          && playing.runState() == RunState.PAUSED) {
        if (transaction != null
            && transaction.plan().playMode() == EngineGamePlayMode.GENMOVE
            && !transaction.genmovePauseSettled()
            && (komiState == null || !komiState.pending())) {
          return;
        }
        EngineGameParameterState parameters =
            EngineGameParameterSession.state(ownerOf(transaction));
        if (parameters != null && parameters.unresolved()) {
          // Continue cannot bypass a participant whose parameter is unknown or unmet.
          blocked = true;
        } else {
          if (transaction != null) {
            transaction.setPaused(false);
          }
          publishLocked(
              new EngineGameSnapshot.BatchActive(
                  active.batch(), new GameActivity.Playing(playing.view(), RunState.RUNNING)));
          resumedTransaction = transaction;
        }
      }
    }
    if (blocked) {
      publishChrome(EngineGameChromeTransition.Kind.PARAMETERS_RESUME_BLOCKED);
      return;
    }
    if (resumedTransaction != null) {
      publishChrome(EngineGameChromeTransition.Kind.RESUMED);
      EngineManager.resumeEngineGame(ownerOf(resumedTransaction));
    }
  }

  @Override
  public boolean reviseKomi(double value) {
    EngineGameTransaction captured;
    synchronized (lock) {
      if (!snapshot.playing() || transaction == null) return false;
      captured = transaction;
    }
    return EngineManager.reviseEngineGameKomi(
        ownerOf(captured), value, () -> onKomiChanged(captured));
  }

  public EngineGameKomiState komiState() {
    synchronized (lock) {
      return komiState;
    }
  }

  private void onKomiChanged(EngineGameTransaction captured) {
    boolean failed;
    synchronized (lock) {
      if (transaction != captured) return;
      komiState = EngineManager.engineGameKomiState(ownerOf(captured));
      failed = komiState != null && komiState.failure() != null;
      if (failed) {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
      }
    }
    publishChrome(EngineGameChromeTransition.Kind.KOMI_CHANGED);
  }

  /** Submits one game-parameter apply to the current game's owner; nothing else writes it. */
  public EngineGameParameterRevision reviseParameters(EngineGameParameterRequest request) {
    EngineGameTransaction captured;
    synchronized (lock) {
      if (!snapshot.playing() || transaction == null) {
        return EngineGameParameterRevision.NOT_PLAYING;
      }
      captured = transaction;
    }
    return EngineGameParameterSession.revise(
        ownerOf(captured), request, () -> onParametersChanged(captured));
  }

  /** The playing game's parameters by participant position, or null without one. */
  public EngineGameParameterState parameterState() {
    EngineGameTransaction captured;
    synchronized (lock) {
      if (!snapshot.playing()) {
        return null;
      }
      captured = transaction;
    }
    return EngineGameParameterSession.state(ownerOf(captured));
  }

  /** Last batch whose participants could not be restored; their processes were stopped. */
  public String parameterRestoreFailure() {
    synchronized (lock) {
      return parameterRestoreFailure;
    }
  }

  public void reportParameterRestoreFailure(String detail) {
    synchronized (lock) {
      parameterRestoreFailure = detail;
    }
    publishChrome(EngineGameChromeTransition.Kind.PARAMETERS_RESTORE_FAILED);
  }

  private void onParametersChanged(EngineGameTransaction captured) {
    boolean paused = false;
    synchronized (lock) {
      if (transaction != captured) return;
      EngineGameParameterState state = EngineGameParameterSession.state(ownerOf(captured));
      if (state != null
          && state.unresolved()
          && snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing playing
          && playing.runState() == RunState.RUNNING) {
        captured.setPaused(true);
        publishLocked(
            new EngineGameSnapshot.BatchActive(
                active.batch(), new GameActivity.Playing(playing.view(), RunState.PAUSED)));
        paused = true;
      }
    }
    if (paused) {
      publishChrome(EngineGameChromeTransition.Kind.PAUSED);
    }
    publishChrome(EngineGameChromeTransition.Kind.PARAMETERS_CHANGED);
  }

  @Override
  public void reviseBatchLimit(int gameCount) {
    EngineGameChromeTransition.Kind chromeKind = null;
    synchronized (lock) {
      if (batch == null || !(snapshot instanceof EngineGameSnapshot.BatchActive)) {
        return;
      }
      batch.setBatchLimit(gameCount);
      EngineGameSnapshot.BatchActive active = (EngineGameSnapshot.BatchActive) snapshot;
      if (pendingSuccessor
          && active.activity() instanceof GameActivity.BetweenGames
          && !batch.shouldCreateSuccessor()) {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
        chromeKind = EngineGameChromeTransition.Kind.BATCH_ENDED;
      } else {
        publishLocked(new EngineGameSnapshot.BatchActive(batch.summary(), active.activity()));
      }
    }
    if (chromeKind != null) {
      publishChrome(chromeKind);
    }
  }

  @Override
  public EngineGameSnapshot current() {
    synchronized (lock) {
      return snapshot;
    }
  }

  public EngineGamePlan firstPlan() {
    synchronized (lock) {
      return plan;
    }
  }

  public EngineGameTransaction transaction() {
    synchronized (lock) {
      return transaction;
    }
  }

  public boolean successorPending() {
    synchronized (lock) {
      return pendingSuccessor;
    }
  }

  public BatchSummary lastSummary() {
    synchronized (lock) {
      return lastSummary;
    }
  }

  public EngineGameCompletionFacts lastCompletion() {
    synchronized (lock) {
      return lastCompletion;
    }
  }

  /** One-shot immutable save input for the exact current history. Does not update batch totals. */
  public EngineGameSaveSnapshot captureSaveSnapshot() {
    synchronized (lock) {
      GameInfo info = currentGameInfo();
      EngineGameRecordContext context = info == null ? null : info.engineGameRecordContext();
      if (context == null && plan != null) {
        context = contextFromPlan(plan);
        if (info != null) {
          info.attachEngineGameRecordContext(context);
        }
      }
      if (context == null) {
        return null;
      }
      long[] visits = sideVisits();
      EngineGameSaveSnapshot snapshot =
          new EngineGameSaveSnapshot(
              context,
              displayName(context.black(), context.blackIndex()),
              displayName(context.white(), context.whiteIndex()),
              engineTime(context.blackIndex()),
              engineTime(context.whiteIndex()),
              visits[0],
              visits[1],
              moveTime(context.blackIndex()),
              moveTime(context.whiteIndex()));
      if (info != null) {
        info.setEngineGameSaveSnapshot(snapshot);
      }
      return snapshot;
    }
  }

  /**
   * Routes a normal per-game outcome. Duplicate or stale owner tokens are inert. This is not {@link
   * #stop()}.
   */
  public boolean complete(GameOutcome outcome, Object ownerToken, int participantIndex) {
    Objects.requireNonNull(outcome, "outcome");
    EngineGameTransaction product;
    EngineGameChromeTransition.Kind chromeKind = null;
    synchronized (lock) {
      product = transaction;
      if (product != null) {
        if (ownerToken != null
            && product.lifecycle() != null
            && !product.lifecycle().sameOwner(ownerToken)) {
          return false;
        }
        if (!product.claimComplete()) {
          return false;
        }
        recordCompletionLocked(outcome, product);
        komiState = null;
        if (batch != null && batch.shouldCreateSuccessor()) {
          pendingSuccessor = true;
          publishLocked(
              new EngineGameSnapshot.BatchActive(batch.summary(), new GameActivity.BetweenGames()));
          chromeKind = EngineGameChromeTransition.Kind.BETWEEN_GAMES;
        } else {
          pendingSuccessor = false;
          plan = null;
          transaction = null;
          publishLocked(new EngineGameSnapshot.Idle());
          chromeKind = EngineGameChromeTransition.Kind.BATCH_ENDED;
        }
      }
    }
    if (product != null) {
      EngineManager.syncProductBatchSummaryReaders();
    }
    if (chromeKind != null) {
      publishChrome(chromeKind);
    }
    int index = participantIndex;
    if (index < 0 && outcome instanceof GameOutcome.Resign resign && product != null) {
      ParticipantBinding binding =
          resign.side() == EngineGameSide.BLACK ? product.blackBinding() : product.whiteBinding();
      if (binding != null) {
        index = binding.catalogIndex();
      }
    }
    boolean stopped = EngineManager.stopEngineGameIfCurrent(ownerToken, index, false);
    return (product != null && product.alreadyCompleted()) || stopped;
  }

  /**
   * Called after the exact owner retirement callback. Creates the next GamePlan only then.
   *
   * @return false when a pending successor failed to start
   */
  public boolean onOwnerRetired() {
    EngineGamePlan nextPlan = null;
    EngineGameChromeTransition.Kind chromeKind = null;
    synchronized (lock) {
      boolean betweenGames =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.BetweenGames;
      if (!pendingSuccessor || batch == null || !betweenGames) {
        pendingSuccessor = false;
        return true;
      }
      if (!batch.shouldCreateSuccessor()) {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
        chromeKind = EngineGameChromeTransition.Kind.BATCH_ENDED;
      } else {
        pendingSuccessor = false;
        batch.beginSuccessor();
        plan = createPlan(batch);
        transaction = new EngineGameTransaction(plan);
        nextPlan = plan;
        publishLocked(
            new EngineGameSnapshot.BatchActive(
                batch.summary(), new GameActivity.Starting(plan.view())));
      }
    }
    if (chromeKind == EngineGameChromeTransition.Kind.BATCH_ENDED) {
      publishChrome(chromeKind);
      return true;
    }
    EngineManager manager = Lizzie.engineManager;
    boolean started = manager != null && manager.startEngineGame(nextPlan, false);
    if (!started) {
      synchronized (lock) {
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
      }
      publishChrome(EngineGameChromeTransition.Kind.LATER_GAME_FAILED);
      return false;
    }
    publishChrome(EngineGameChromeTransition.Kind.STARTING);
    return true;
  }

  private static Object ownerOf(EngineGameTransaction product) {
    if (product == null || product.lifecycle() == null) {
      return null;
    }
    return product.lifecycle().ownerToken();
  }

  public void onOwnerPlaying() {
    StartObserver pending = null;
    synchronized (lock) {
      if (batch == null || plan == null) {
        return;
      }
      attachRecordContextToCurrentHistoryLocked();
      publishLocked(
          new EngineGameSnapshot.BatchActive(
              batch.summary(), new GameActivity.Playing(plan.view(), RunState.RUNNING)));
      if (observer != null && !observerCompleted) {
        observerCompleted = true;
        pending = observer;
        observer = null;
      }
    }
    if (pending != null) {
      pending.playing();
    }
    publishChrome(EngineGameChromeTransition.Kind.PLAYING);
  }

  public void onOwnerStartFailed(Throwable cause) {
    StartObserver pending = null;
    synchronized (lock) {
      boolean starting =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.Starting;
      if (!starting) {
        return;
      }
      if (matchRulesSnapshot != null
          && (matchRulesSnapshot.phase() == MatchRulesSnapshot.Phase.PREPARING
              || matchRulesSnapshot.phase() == MatchRulesSnapshot.Phase.PLAYING)) {
        // Keep the last prepare observations. A failed start must not create a Playing/Completed
        // record.
        if (matchRulesSnapshot.black().status() != EngineRulesResult.Status.PENDING
            || matchRulesSnapshot.white().status() != EngineRulesResult.Status.PENDING) {
          matchRulesSnapshot =
              MatchRulesSnapshot.of(
                  MatchRulesSnapshot.Phase.FAILED,
                  matchRulesSnapshot.target(),
                  toSideResult(matchRulesSnapshot.black()),
                  toSideResult(matchRulesSnapshot.white()),
                  matchRulesSnapshot.outcome() == null
                      ? MatchRulesAdmission.Outcome.REJECT
                      : matchRulesSnapshot.outcome());
        }
      }
      if (observer != null && !observerCompleted) {
        observerCompleted = true;
        pending = observer;
        observer = null;
        clearAcceptedLocked();
      } else {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
      }
      publishLocked(new EngineGameSnapshot.Idle());
    }
    publishChrome(
        pending != null
            ? EngineGameChromeTransition.Kind.START_FAILED
            : EngineGameChromeTransition.Kind.LATER_GAME_FAILED);
    if (pending != null) {
      pending.startFailed(startFailureFrom(cause));
    }
  }

  public void resetForTest() {
    synchronized (lock) {
      observer = null;
      observerCompleted = false;
      lastSummary = null;
      lastCompletion = null;
      chrome = SwingEngineGameChrome.INSTANCE;
      matchRulesConsent = featurecat.lizzie.gui.SwingMatchRulesConsent.INSTANCE;
      matchRulesSnapshot = null;
      grantedMatchRulesConsent = null;
      clearAcceptedLocked();
      snapshot = new EngineGameSnapshot.Idle();
    }
  }

  public void replaceChromeForTest(EngineGameChrome chrome) {
    synchronized (lock) {
      this.chrome = chrome == null ? SwingEngineGameChrome.INSTANCE : chrome;
    }
  }

  public void publishSnapshotForTest(EngineGameSnapshot snapshot) {
    synchronized (lock) {
      this.snapshot = snapshot == null ? new EngineGameSnapshot.Idle() : snapshot;
    }
  }

  private void recordCompletionLocked(GameOutcome outcome, EngineGameTransaction product) {
    if (batch == null || product == null || product.plan() == null) {
      return;
    }
    EngineGamePlan current = product.plan();
    boolean firstPlayedBlack = current.firstIndex() == current.blackIndex();
    long[] visits = sideVisits();
    long firstVisits = firstPlayedBlack ? visits[0] : visits[1];
    long secondVisits = firstPlayedBlack ? visits[1] : visits[0];
    lastCompletion =
        new EngineGameCompletionFacts(
            outcome,
            current.gameOrdinal(),
            current.openingIndex(),
            firstPlayedBlack,
            engineTime(current.firstIndex()),
            engineTime(current.secondIndex()),
            firstVisits,
            secondVisits);
    batch.apply(lastCompletion);
    lastSummary = batch.summary();
    freezeRecordOnCurrentHistoryLocked(lastCompletion);
  }

  private void clearAcceptedLocked() {
    komiState = null;
    pendingSuccessor = false;
    batch = null;
    plan = null;
    transaction = null;
  }

  private void publishLocked(EngineGameSnapshot next) {
    snapshot = next;
    if (next instanceof EngineGameSnapshot.BatchActive active) {
      lastSummary = active.batch();
    }
  }

  private void publishChrome(EngineGameChromeTransition.Kind kind) {
    EngineGameChrome target;
    EngineGameSnapshot published;
    synchronized (lock) {
      target = chrome;
      published = snapshot;
    }
    if (target != null) {
      target.publish(new EngineGameChromeTransition(kind, published));
    }
  }

  private static Rejection validateSpec(EngineGameBatchSpec spec) {
    if (spec.first().equals(spec.second())) {
      return new Rejection.InvalidParticipantCombination();
    }
    if (spec.playMode() == EngineGamePlayMode.ANALYSIS) {
      if (invalidAnalysisLimits(spec.firstLimits()) || invalidAnalysisLimits(spec.secondLimits())) {
        return new Rejection.InvalidAnalysisLimits();
      }
    }
    return null;
  }

  private static boolean invalidAnalysisLimits(EngineGameSideLimits limits) {
    return limits.timeSeconds() <= 0 && limits.visits() <= 0 && limits.firstMoveVisits() <= 0;
  }

  private static Rejection ownerRejection(EngineManager manager, EngineGamePlan plan) {
    if (DesktopTimeControl.rejectsEngineGame(
        manager.engineList,
        plan.blackIndex(),
        plan.whiteIndex(),
        EngineGameTimeModes.sideMode(plan.blackLimits().timeMode()),
        EngineGameTimeModes.sideMode(plan.whiteLimits().timeMode()))) {
      return new Rejection.UnsupportedMode();
    }
    return new Rejection.OccupiedLifecycle();
  }

  private void ensureOutputIdentity(EngineManager manager, EngineGamePlan currentPlan) {
    synchronized (lock) {
      if (batch == null || currentPlan == null) {
        return;
      }
      String timestamp = batch.timestamp();
      if (timestamp.isEmpty()) {
        timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
      }
      String name = batch.batchGameName();
      if (name.isEmpty()) {
        name = currentPlan.output().batchName();
      }
      if (name.isEmpty() && currentPlan.batch()) {
        name =
            manager.getEngineName(currentPlan.firstIndex())
                + "_VS_"
                + manager.getEngineName(currentPlan.secondIndex())
                + "_"
                + timestamp;
        name = name.replaceAll("[/\\\\:*?|]", ".");
        name = name.replaceAll("[\"<>]", "'");
      }
      batch.rememberOutputIdentity(name, timestamp);
    }
  }

  public String outputBatchName() {
    synchronized (lock) {
      return batch == null ? "" : batch.batchGameName();
    }
  }

  public String outputTimestamp() {
    synchronized (lock) {
      return batch == null ? "" : batch.timestamp();
    }
  }

  private static void presentRejection(Rejection rejection) {
    if (rejection instanceof Rejection.InvalidParticipantCombination) {
      showEngineMessage("EngineManager.engineGameSameEngine");
    } else if (rejection instanceof Rejection.InvalidAnalysisLimits) {
      showEngineMessage("EngineManager.engineGameBlackSettingWrong");
    }
  }

  private static void showEngineMessage(String key) {
    if (Lizzie.resourceBundle == null || Lizzie.frame == null) {
      return;
    }
    try {
      Utils.showMsg(Lizzie.resourceBundle.getString(key));
    } catch (RuntimeException ignored) {
      // Test doubles and incomplete Swing peers cannot host HtmlMessage.
    }
  }

  private static EngineGamePlan createPlan(EngineGameBatchState batch) {
    EngineGameBatchSpec spec = batch.spec();
    boolean firstIsBlack = batch.firstIsBlack();
    EngineParticipantIdentity black = firstIsBlack ? spec.first() : spec.second();
    EngineParticipantIdentity white = firstIsBlack ? spec.second() : spec.first();
    int blackIndex = firstIsBlack ? batch.firstIndex() : batch.secondIndex();
    int whiteIndex = firstIsBlack ? batch.secondIndex() : batch.firstIndex();
    EngineGameSideLimits blackLimits = firstIsBlack ? spec.firstLimits() : spec.secondLimits();
    EngineGameSideLimits whiteLimits = firstIsBlack ? spec.secondLimits() : spec.firstLimits();
    List<EngineGameMove> openingMoves = List.of();
    int openingIndex = -1;
    boolean continueGame = false;
    if (spec.opening() instanceof EngineGameOpeningPlan.ContinuePosition continuePosition) {
      openingMoves = continuePosition.moves();
      continueGame = true;
    } else if (spec.opening() instanceof EngineGameOpeningPlan.SgfCatalog catalog
        && !catalog.openings().isEmpty()) {
      if (catalog.strategy() == SgfOpeningStrategy.RANDOM) {
        openingIndex = new Random().nextInt(catalog.openings().size());
      } else {
        openingIndex = batch.sequentialOpeningCursor();
        if (openingIndex < 0 || openingIndex >= catalog.openings().size()) {
          openingIndex = 0;
        }
        batch.setSequentialOpeningCursor(openingIndex + 1);
      }
      openingMoves = catalog.openings().get(openingIndex);
    }
    if (openingIndex >= 0 && LizzieFrame.toolbar != null) {
      LizzieFrame.toolbar.currentEnginePkSgfNum = openingIndex;
    }
    return new EngineGamePlan(
        black,
        white,
        blackIndex,
        whiteIndex,
        batch.firstIndex(),
        batch.secondIndex(),
        spec.playMode(),
        blackLimits,
        whiteLimits,
        spec.komi(),
        spec.handicap(),
        openingMoves,
        openingIndex,
        continueGame,
        spec.exchangeColors(),
        batch.gameOrdinal(),
        spec.batch(),
        batch.batchLimit(),
        spec.maxMoveLimitEnabled(),
        spec.maxMoves(),
        spec.output(),
        spec.matchRules(),
        spec.openingParameters());
  }

  private void attachRecordContextToCurrentHistoryLocked() {
    if (plan == null) {
      return;
    }
    GameInfo info = currentGameInfo();
    if (info == null || info.engineGameRecord() != null) {
      return;
    }
    info.attachEngineGameRecordContext(contextFromPlan(plan));
  }

  private void freezeRecordOnCurrentHistoryLocked(EngineGameCompletionFacts facts) {
    if (matchRulesSnapshot != null
        && (matchRulesSnapshot.phase() == MatchRulesSnapshot.Phase.PLAYING
            || matchRulesSnapshot.phase() == MatchRulesSnapshot.Phase.COMPLETED)) {
      matchRulesSnapshot = matchRulesSnapshot.completed();
    }
    GameInfo info = currentGameInfo();
    if (info == null || facts == null) {
      return;
    }
    EngineGameRecordContext context = null;
    if (plan != null) {
      context = contextFromPlan(plan);
      info.attachEngineGameRecordContext(context);
    } else {
      context = info.engineGameRecordContext();
    }
    if (context == null) {
      return;
    }
    info.freezeEngineGameRecord(
        new EngineGameRecord(
            context,
            facts,
            displayName(context.black(), context.blackIndex()),
            displayName(context.white(), context.whiteIndex()),
            matchRulesSnapshot));
  }

  private EngineGameRecordContext contextFromPlan(EngineGamePlan current) {
    return new EngineGameRecordContext(
        current,
        descriptor(current.black(), current.blackIndex()),
        descriptor(current.white(), current.whiteIndex()),
        matchRulesSnapshot);
  }

  private static EngineGameParticipantDescriptor descriptor(
      EngineParticipantIdentity identity, int index) {
    Leelaz engine = engineAt(index);
    String displayName = "";
    boolean katago = false;
    boolean sai = false;
    KataGoRules.Summary rules = null;
    if (engine != null) {
      if (engine.oriEnginename != null && !engine.oriEnginename.isEmpty()) {
        displayName = engine.oriEnginename;
      } else if (engine.currentEnginename != null) {
        displayName = engine.currentEnginename;
      }
      katago = engine.isKatago;
      sai = engine.isSai;
      rules = engine.rulesSummary;
    }
    if (displayName.isEmpty() && identity != null) {
      displayName = identity.name();
    }
    return new EngineGameParticipantDescriptor(identity, displayName, katago, sai, rules);
  }

  private static String displayName(EngineGameParticipantDescriptor descriptor, int index) {
    Leelaz engine = engineAt(index);
    if (engine != null) {
      if (engine.oriEnginename != null && !engine.oriEnginename.isEmpty()) {
        return engine.oriEnginename;
      }
      if (engine.currentEnginename != null && !engine.currentEnginename.isEmpty()) {
        return engine.currentEnginename;
      }
    }
    if (descriptor != null && !descriptor.displayName().isEmpty()) {
      return descriptor.displayName();
    }
    if (descriptor != null) {
      return descriptor.identity().name();
    }
    return "";
  }

  private static GameInfo currentGameInfo() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getGameInfo();
  }

  private static Leelaz engineAt(int index) {
    EngineManager manager = Lizzie.engineManager;
    if (manager == null
        || manager.engineList == null
        || index < 0
        || index >= manager.engineList.size()) {
      return null;
    }
    return manager.engineList.get(index);
  }

  private static long moveTime(int index) {
    Leelaz engine = engineAt(index);
    return engine == null ? 0L : engine.pkMoveTime;
  }

  private static long engineTime(int index) {
    Leelaz engine = engineAt(index);
    return engine == null ? 0L : engine.pkMoveTimeGame;
  }

  private static long[] sideVisits() {
    long blackPlayouts = 0;
    long whitePlayouts = 0;
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return new long[] {0L, 0L};
    }
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    if (node == null) {
      return new long[] {0L, 0L};
    }
    while (node.next().isPresent()) {
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.WHITE)) {
        blackPlayouts += node.getData().getPlayouts();
      }
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.BLACK)) {
        whitePlayouts += node.getData().getPlayouts();
      }
      node = node.next().get();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.WHITE)) {
      blackPlayouts += node.getData().getPlayouts();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.BLACK)) {
      whitePlayouts += node.getData().getPlayouts();
    }
    return new long[] {blackPlayouts, whitePlayouts};
  }

  private static StartFailure startFailureFrom(Throwable cause) {
    if (cause instanceof MatchRulesPrepareException) {
      return new StartFailure.MatchRulesFailed(cause.getMessage());
    }
    String detail = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
    if (detail.contains("deadline expired")) {
      return new StartFailure.Timeout();
    }
    if (detail.contains("Match rules") || detail.contains("match-rules")) {
      return new StartFailure.MatchRulesFailed(detail);
    }
    return new StartFailure.ParticipantStartupFailed();
  }

  public MatchRulesSnapshot matchRulesSnapshot() {
    synchronized (lock) {
      return matchRulesSnapshot;
    }
  }

  public void publishMatchRulesSnapshot(MatchRulesSnapshot snapshot) {
    synchronized (lock) {
      this.matchRulesSnapshot = snapshot;
    }
  }

  public MatchRulesAdmission.ConsentKey grantedMatchRulesConsent() {
    synchronized (lock) {
      return grantedMatchRulesConsent;
    }
  }

  public void grantMatchRulesConsent(MatchRulesAdmission.ConsentKey key) {
    synchronized (lock) {
      grantedMatchRulesConsent = key;
    }
  }

  public void installMatchRulesConsentForTest(MatchRulesConsent consent) {
    synchronized (lock) {
      matchRulesConsent = consent;
    }
  }

  public boolean requestUnverifiedConsent(MatchRulesAdmission.Decision decision) {
    MatchRulesConsent consent;
    synchronized (lock) {
      consent = matchRulesConsent;
    }
    return consent != null && consent.confirmUnverified(decision);
  }

  public void cancelSuccessorAfterRestoreFailure() {
    synchronized (lock) {
      pendingSuccessor = false;
      if (snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.BetweenGames) {
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
      }
    }
  }

  private static MatchRulesAdmission.SideResult toSideResult(MatchRulesSnapshot.Side side) {
    return new MatchRulesAdmission.SideResult(
        side.identity(),
        side.canSet(),
        side.canQuery(),
        side.original(),
        side.observed(),
        side.status(),
        side.reason(),
        false);
  }
}
