package featurecat.lizzie.analysis;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/** Owns single-stream tracking requests and their immutable display state. */
public final class TrackingAnalysisController {
  static final long PROGRESS_TIMEOUT_MILLIS = 8000L;

  public enum AddResult {
    ADDED,
    DUPLICATE,
    ILLEGAL,
    CONTEXT_MISMATCH,
    LEASE_UNAVAILABLE
  }

  interface Cancellable {
    void cancel();
  }

  interface TimeoutScheduler {
    Cancellable schedule(long delayMillis, Runnable task);
  }

  public static final class Parameters {
    private final int intervalCentiseconds;
    private final int targetVisits;

    public Parameters(int intervalCentiseconds, int targetVisits) {
      if (intervalCentiseconds <= 0 || targetVisits <= 0) {
        throw new IllegalArgumentException("tracking parameters must be positive");
      }
      this.intervalCentiseconds = intervalCentiseconds;
      this.targetVisits = targetVisits;
    }

    public int intervalCentiseconds() {
      return intervalCentiseconds;
    }

    public int targetVisits() {
      return targetVisits;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Parameters)) {
        return false;
      }
      Parameters that = (Parameters) other;
      return intervalCentiseconds == that.intervalCentiseconds && targetVisits == that.targetVisits;
    }

    @Override
    public int hashCode() {
      return Objects.hash(intervalCentiseconds, targetVisits);
    }
  }

  public static final class ReadBoardContext {
    private final Object identity;
    private final long revision;
    private final Object nodeIdentity;
    private final long boardRevision;

    public ReadBoardContext(Object identity, long revision, Object nodeIdentity) {
      this(identity, revision, nodeIdentity, 0L);
    }

    public ReadBoardContext(
        Object identity, long revision, Object nodeIdentity, long boardRevision) {
      this.identity = Objects.requireNonNull(identity, "identity");
      this.revision = revision;
      this.nodeIdentity = Objects.requireNonNull(nodeIdentity, "nodeIdentity");
      this.boardRevision = boardRevision;
    }

    public Object identity() {
      return identity;
    }

    public long revision() {
      return revision;
    }

    public Object nodeIdentity() {
      return nodeIdentity;
    }

    public long boardRevision() {
      return boardRevision;
    }

    private boolean matches(ReadBoardContext other) {
      return other != null
          && identity == other.identity
          && revision == other.revision
          && nodeIdentity == other.nodeIdentity
          && boardRevision == other.boardRevision;
    }
  }

  public static final class Context {
    private final Object historyIdentity;
    private final Object displayNodeIdentity;
    private final int boardWidth;
    private final int boardHeight;
    private final String stonesFingerprint;
    private final boolean blackToPlay;
    private final String rules;
    private final double komi;
    private final Leelaz engine;
    private final long engineIncarnation;
    private final Parameters parameters;
    private final ReadBoardContext readBoardContext;

    public Context(
        Object historyIdentity,
        Object displayNodeIdentity,
        int boardWidth,
        int boardHeight,
        String stonesFingerprint,
        boolean blackToPlay,
        String rules,
        double komi,
        Leelaz engine,
        long engineIncarnation,
        Parameters parameters,
        ReadBoardContext readBoardContext) {
      if (boardWidth <= 0 || boardHeight <= 0 || engineIncarnation <= 0L) {
        throw new IllegalArgumentException("invalid tracking context");
      }
      this.historyIdentity = Objects.requireNonNull(historyIdentity, "historyIdentity");
      this.displayNodeIdentity = Objects.requireNonNull(displayNodeIdentity, "displayNodeIdentity");
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.stonesFingerprint = Objects.requireNonNull(stonesFingerprint, "stonesFingerprint");
      this.blackToPlay = blackToPlay;
      this.rules = Objects.requireNonNull(rules, "rules");
      this.komi = komi;
      this.engine = Objects.requireNonNull(engine, "engine");
      this.engineIncarnation = engineIncarnation;
      this.parameters = Objects.requireNonNull(parameters, "parameters");
      this.readBoardContext = readBoardContext;
    }

    public Object historyIdentity() {
      return historyIdentity;
    }

    public Object displayNodeIdentity() {
      return displayNodeIdentity;
    }

    public int boardWidth() {
      return boardWidth;
    }

    public int boardHeight() {
      return boardHeight;
    }

    public String stonesFingerprint() {
      return stonesFingerprint;
    }

    public boolean blackToPlay() {
      return blackToPlay;
    }

    public String rules() {
      return rules;
    }

    public double komi() {
      return komi;
    }

    public Leelaz engine() {
      return engine;
    }

    public long engineIncarnation() {
      return engineIncarnation;
    }

    public Parameters parameters() {
      return parameters;
    }

    public java.util.Optional<ReadBoardContext> readBoardContext() {
      return java.util.Optional.ofNullable(readBoardContext);
    }

    private boolean matches(Context other) {
      return other != null
          && historyIdentity == other.historyIdentity
          && displayNodeIdentity == other.displayNodeIdentity
          && boardWidth == other.boardWidth
          && boardHeight == other.boardHeight
          && stonesFingerprint.equals(other.stonesFingerprint)
          && blackToPlay == other.blackToPlay
          && rules.equals(other.rules)
          && Double.doubleToLongBits(komi) == Double.doubleToLongBits(other.komi)
          && engine == other.engine
          && engineIncarnation == other.engineIncarnation
          && parameters.equals(other.parameters)
          && (readBoardContext == null
              ? other.readBoardContext == null
              : readBoardContext.matches(other.readBoardContext));
    }
  }

  public static final class DisplaySnapshot {
    private static final DisplaySnapshot EMPTY =
        new DisplaySnapshot(null, 0L, Collections.emptySet(), Collections.emptyMap(), false, false);

    private final Context context;
    private final long generation;
    private final Set<String> selectedPoints;
    private final Map<String, PointResult> results;
    private final boolean active;
    private final boolean frozen;

    private DisplaySnapshot(
        Context context,
        long generation,
        Set<String> selectedPoints,
        Map<String, PointResult> results,
        boolean active,
        boolean frozen) {
      this.context = context;
      this.generation = generation;
      this.selectedPoints = Collections.unmodifiableSet(new LinkedHashSet<String>(selectedPoints));
      this.results = Collections.unmodifiableMap(new LinkedHashMap<String, PointResult>(results));
      this.active = active;
      this.frozen = frozen;
    }

    public Set<String> selectedPoints() {
      return selectedPoints;
    }

    public Context context() {
      return context;
    }

    public long generation() {
      return generation;
    }

    public boolean active() {
      return active;
    }

    public Map<String, PointResult> results() {
      return results;
    }

    public boolean frozen() {
      return frozen;
    }
  }

  public static final class PointResult {
    private final String coordinate;
    private final int visits;
    private final double winrate;
    private final double scoreLead;
    private final boolean completed;

    private PointResult(MoveData move) {
      this.coordinate = move.coordinate;
      this.visits = move.playouts;
      this.winrate = move.winrate;
      this.scoreLead = move.scoreMean;
      this.completed = false;
    }

    private PointResult(PointResult source, boolean completed) {
      this.coordinate = source.coordinate;
      this.visits = source.visits;
      this.winrate = source.winrate;
      this.scoreLead = source.scoreLead;
      this.completed = completed;
    }

    public String coordinate() {
      return coordinate;
    }

    public int visits() {
      return visits;
    }

    public double winrate() {
      return winrate;
    }

    public double scoreLead() {
      return scoreLead;
    }

    public boolean completed() {
      return completed;
    }

    private PointResult asCompleted() {
      return completed ? this : new PointResult(this, true);
    }
  }

  private static final class PointAttempt {
    private final long generation;
    private final String coordinate;
    private Leelaz.TrackingStreamLease lease;
    private Cancellable timeout;
    private long timeoutToken;
    private PointResult result;
    private boolean cancelled;
    private boolean restorePonderOnClose;
    private boolean acquisitionValidated;
    private boolean ready;
    private boolean requestSent;
    private Leelaz.TrackingReleaseDisposition disposition =
        Leelaz.TrackingReleaseDisposition.ACTIVE;

    private PointAttempt(long generation, String coordinate) {
      this.generation = generation;
      this.coordinate = coordinate;
    }
  }

  private final TimeoutScheduler timeoutScheduler;
  private final Runnable displayChanged;
  private final LinkedHashSet<String> selectedPoints = new LinkedHashSet<>();
  private final Deque<String> pendingPoints = new ArrayDeque<>();
  private final LinkedHashMap<String, PointResult> results = new LinkedHashMap<>();
  private Context context;
  private long generation;
  private PointAttempt current;
  private Leelaz.TrackingStreamLeaseReceipt initialReceipt;
  private volatile DisplaySnapshot snapshot = DisplaySnapshot.EMPTY;

  public TrackingAnalysisController() {
    this(new DaemonTimeoutScheduler(), () -> {});
  }

  public TrackingAnalysisController(Runnable displayChanged) {
    this(new DaemonTimeoutScheduler(), displayChanged);
  }

  TrackingAnalysisController(TimeoutScheduler timeoutScheduler) {
    this(timeoutScheduler, () -> {});
  }

  TrackingAnalysisController(TimeoutScheduler timeoutScheduler, Runnable displayChanged) {
    this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
    this.displayChanged = Objects.requireNonNull(displayChanged, "displayChanged");
  }

  public synchronized AddResult addPoint(String coordinate, Context requestedContext) {
    return addPoint(coordinate, requestedContext, null);
  }

  synchronized AddResult addPoint(
      String coordinate, Context requestedContext, java.util.function.BooleanSupplier validator) {
    Objects.requireNonNull(requestedContext, "requestedContext");
    String normalized = normalizeCoordinate(coordinate, requestedContext);
    if (normalized == null) {
      return AddResult.ILLEGAL;
    }
    if (context != null && !context.matches(requestedContext)) {
      return AddResult.CONTEXT_MISMATCH;
    }
    if (current != null
        && (current.cancelled || current.disposition != Leelaz.TrackingReleaseDisposition.ACTIVE)) {
      return AddResult.LEASE_UNAVAILABLE;
    }
    if (current == null && snapshot.frozen()) {
      clearPointState();
      initialReceipt = null;
      publishEmptySnapshot();
    }
    if (selectedPoints.contains(normalized)) {
      return AddResult.DUPLICATE;
    }

    context = requestedContext;
    selectedPoints.add(normalized);
    if (current != null) {
      pendingPoints.addFirst(normalized);
      publishSnapshot(true, false);
      return AddResult.ADDED;
    }
    return startPoint(normalized, validator);
  }

  public synchronized boolean removePoint(String coordinate) {
    if (context == null) {
      return false;
    }
    String normalized = normalizeCoordinate(coordinate, context);
    if (normalized == null || !selectedPoints.remove(normalized)) {
      return false;
    }
    results.remove(normalized);
    if (current != null && current.coordinate.equals(normalized)) {
      current.cancelled = true;
      current.restorePonderOnClose = true;
      cancelTimeout(current);
      publishSnapshot(false, false);
      current.lease.release();
    } else {
      pendingPoints.remove(normalized);
      publishSnapshot(current != null, false);
    }
    if (selectedPoints.isEmpty() && current == null) {
      context = null;
    }
    return true;
  }

  public synchronized void clear() {
    PointAttempt attempt = current;
    clearPointState();
    if (attempt == null) {
      context = null;
      initialReceipt = null;
      publishEmptySnapshot();
      return;
    }
    attempt.cancelled = true;
    attempt.restorePonderOnClose = true;
    cancelTimeout(attempt);
    publishEmptySnapshot();
    if (attempt.lease != null) {
      attempt.lease.release();
    }
  }

  public synchronized void contextChanged(Context currentContext) {
    if (context != null && (currentContext == null || !context.matches(currentContext))) {
      clearState();
    }
  }

  private void clearState() {
    PointAttempt attempt = current;
    generation++;
    current = null;
    if (attempt != null) {
      attempt.cancelled = true;
      cancelTimeout(attempt);
    }
    clearPointState();
    context = null;
    initialReceipt = null;
    publishEmptySnapshot();
    if (attempt != null && attempt.lease != null) {
      attempt.lease.release();
    }
  }

  private AddResult startPoint(String coordinate) {
    return startPoint(coordinate, null);
  }

  private AddResult startPoint(
      String coordinate, java.util.function.BooleanSupplier acquisitionValidator) {
    PointAttempt attempt = new PointAttempt(++generation, coordinate);
    current = attempt;
    publishSnapshot(true, false);
    Leelaz.TrackingStreamLeaseAcquisition acquisition =
        context.engine.acquireTrackingStreamLease(
            line -> handleLine(attempt.generation, line),
            lease -> handleReady(attempt.generation, lease),
            lease -> handleClosed(attempt.generation, lease),
            new LeaseObserver(attempt.generation));
    if (current != attempt
        || acquisition.availability() != Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE
        || acquisition.receipt() == null
        || acquisition.receipt().engine() != context.engine
        || acquisition.receipt().engineIncarnation() != context.engineIncarnation) {
      if (acquisition.lease() != null) {
        acquisition.lease().release();
      }
      if (current == attempt) {
        current = null;
      }
      clearPointState();
      context = null;
      initialReceipt = null;
      publishEmptySnapshot();
      return AddResult.LEASE_UNAVAILABLE;
    }
    attempt.lease = acquisition.lease();
    boolean validAfterAcquisition =
        acquisitionValidator == null || acquisitionValidator.getAsBoolean();
    if (!validAfterAcquisition || current != attempt) {
      attempt.cancelled = true;
      if (attempt.lease != null) {
        attempt.lease.release();
      }
      if (current == attempt) {
        current = null;
        clearPointState();
        context = null;
        initialReceipt = null;
        publishEmptySnapshot();
      }
      return AddResult.LEASE_UNAVAILABLE;
    }
    attempt.acquisitionValidated = true;
    if (initialReceipt == null) {
      initialReceipt = acquisition.receipt();
    }
    if (attempt.ready) {
      sendTrackingRequest(attempt);
    }
    return AddResult.ADDED;
  }

  public DisplaySnapshot snapshot() {
    return snapshot;
  }

  private synchronized void handleReady(long expectedGeneration, Leelaz.TrackingStreamLease lease) {
    PointAttempt attempt = current;
    if (attempt == null
        || attempt.generation != expectedGeneration
        || (attempt.lease != null && attempt.lease != lease)) {
      lease.release();
      return;
    }
    attempt.lease = lease;
    attempt.ready = true;
    if (!attempt.acquisitionValidated) {
      return;
    }
    sendTrackingRequest(attempt);
  }

  private void sendTrackingRequest(PointAttempt attempt) {
    if (current != attempt || attempt.requestSent || attempt.cancelled) {
      return;
    }
    attempt.requestSent = true;
    String command =
        "kata-analyze "
            + context.parameters.intervalCentiseconds()
            + " allow B "
            + attempt.coordinate
            + " 1 allow W "
            + attempt.coordinate
            + " 1";
    if (attempt.lease.send(command) && current == attempt) {
      scheduleProgressTimeout(attempt);
    }
  }

  private synchronized void handleLine(long expectedGeneration, String line) {
    PointAttempt attempt = current;
    if (attempt == null
        || attempt.generation != expectedGeneration
        || attempt.cancelled
        || attempt.disposition != Leelaz.TrackingReleaseDisposition.ACTIVE
        || line == null
        || !line.startsWith("info ")) {
      return;
    }
    PointResult parsed;
    try {
      parsed = new PointResult(MoveData.fromInfoKatago(line.substring(5)));
    } catch (RuntimeException parseFailure) {
      return;
    }
    if (!attempt.coordinate.equalsIgnoreCase(parsed.coordinate)
        || parsed.visits <= 0
        || (attempt.result != null && parsed.visits <= attempt.result.visits)) {
      return;
    }
    attempt.result = parsed;
    results.put(attempt.coordinate, parsed);
    cancelTimeout(attempt);
    if (parsed.visits >= context.parameters.targetVisits()) {
      attempt.lease.release();
    } else {
      scheduleProgressTimeout(attempt);
    }
    publishSnapshot(true, false);
  }

  private synchronized void handleClosed(
      long expectedGeneration, Leelaz.TrackingStreamLease lease) {
    PointAttempt attempt = current;
    if (attempt == null
        || attempt.generation != expectedGeneration
        || (attempt.lease != null && attempt.lease != lease)) {
      return;
    }
    attempt.lease = lease;
    cancelTimeout(attempt);
    current = null;
    if (attempt.disposition != Leelaz.TrackingReleaseDisposition.ACTIVE) {
      if (attempt.disposition == Leelaz.TrackingReleaseDisposition.CLEARED) {
        context = null;
      }
      return;
    }
    boolean completed =
        !attempt.cancelled
            && lease.failureReason().isEmpty()
            && attempt.result != null
            && attempt.result.visits >= context.parameters.targetVisits();
    boolean cleanUserCancellation = attempt.restorePonderOnClose && lease.failureReason().isEmpty();
    if (!completed) {
      selectedPoints.remove(attempt.coordinate);
      results.remove(attempt.coordinate);
    } else {
      attempt.result = attempt.result.asCompleted();
      results.put(attempt.coordinate, attempt.result);
    }
    String next = pendingPoints.pollFirst();
    if (next != null) {
      startPoint(next);
    } else {
      publishSnapshot(false, false);
      Leelaz.TrackingStreamLeaseReceipt handbackReceipt = initialReceipt;
      initialReceipt = null;
      if ((completed || cleanUserCancellation)
          && lease.disposition() == Leelaz.TrackingReleaseDisposition.ACTIVE
          && handbackReceipt != null
          && context.engine == handbackReceipt.engine()
          && context.engineIncarnation == handbackReceipt.engineIncarnation()) {
        context.engine.restorePonderAfterTracking(handbackReceipt);
      }
      if (selectedPoints.isEmpty()) {
        context = null;
        publishEmptySnapshot();
      }
    }
  }

  private void scheduleProgressTimeout(PointAttempt attempt) {
    long token = ++attempt.timeoutToken;
    attempt.timeout =
        timeoutScheduler.schedule(
            PROGRESS_TIMEOUT_MILLIS, () -> handleTimeout(attempt.generation, token));
  }

  private synchronized void handleTimeout(long expectedGeneration, long expectedToken) {
    PointAttempt attempt = current;
    if (attempt == null
        || attempt.generation != expectedGeneration
        || attempt.timeoutToken != expectedToken
        || attempt.disposition != Leelaz.TrackingReleaseDisposition.ACTIVE) {
      return;
    }
    attempt.timeout = null;
    attempt.timeoutToken++;
    attempt.lease.release();
  }

  private synchronized void handleDisposition(
      long expectedGeneration, Leelaz.TrackingReleaseDisposition disposition) {
    PointAttempt attempt = current;
    if (attempt == null
        || attempt.generation != expectedGeneration
        || attempt.disposition.ordinal() >= disposition.ordinal()) {
      return;
    }
    attempt.disposition = disposition;
    initialReceipt = null;
    cancelTimeout(attempt);
    if (disposition == Leelaz.TrackingReleaseDisposition.FROZEN_BY_SAFE) {
      pendingPoints.clear();
      selectedPoints.retainAll(results.keySet());
      publishSnapshot(false, !results.isEmpty());
    } else {
      clearPointState();
      publishSnapshot(false, false);
    }
  }

  private static void cancelTimeout(PointAttempt attempt) {
    attempt.timeoutToken++;
    if (attempt.timeout != null) {
      attempt.timeout.cancel();
      attempt.timeout = null;
    }
  }

  private void clearPointState() {
    pendingPoints.clear();
    selectedPoints.clear();
    results.clear();
  }

  private void publishSnapshot(boolean active, boolean frozen) {
    snapshot = new DisplaySnapshot(context, generation, selectedPoints, results, active, frozen);
    displayChanged.run();
  }

  private void publishEmptySnapshot() {
    snapshot = DisplaySnapshot.EMPTY;
    displayChanged.run();
  }

  private static String normalizeCoordinate(String coordinate, Context context) {
    if (coordinate == null) {
      return null;
    }
    String normalized = coordinate.trim().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.matches("[A-HJ-Z][1-9][0-9]*")) {
      return null;
    }
    char column = normalized.charAt(0);
    int x = column - 'A' - (column > 'I' ? 1 : 0);
    int row;
    try {
      row = Integer.parseInt(normalized.substring(1));
    } catch (NumberFormatException invalidRow) {
      return null;
    }
    if (x < 0 || x >= context.boardWidth || row < 1 || row > context.boardHeight) {
      return null;
    }
    return normalized;
  }

  private final class LeaseObserver implements Leelaz.TrackingReleaseDispositionObserver {
    private final long expectedGeneration;

    private LeaseObserver(long expectedGeneration) {
      this.expectedGeneration = expectedGeneration;
    }

    @Override
    public void onDispositionChanged(Leelaz.TrackingReleaseDisposition disposition) {
      handleDisposition(expectedGeneration, disposition);
    }

    @Override
    public void onReleaseClaimed(Leelaz.TrackingReleaseReason reason) {
      handleDisposition(
          expectedGeneration,
          reason == Leelaz.TrackingReleaseReason.SAFE_READ_ONLY_QUERY
              ? Leelaz.TrackingReleaseDisposition.FROZEN_BY_SAFE
              : Leelaz.TrackingReleaseDisposition.CLEARED);
    }
  }

  private static final class DaemonTimeoutScheduler implements TimeoutScheduler {
    private final Timer timer = new Timer("lizzie-tracking-analysis-progress-timeout", true);

    @Override
    public Cancellable schedule(long delayMillis, Runnable task) {
      TimerTask timerTask =
          new TimerTask() {
            @Override
            public void run() {
              task.run();
            }
          };
      timer.schedule(timerTask, delayMillis);
      return timerTask::cancel;
    }
  }
}
