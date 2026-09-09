package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/** Owns attention points and cumulative progress in the current ordinary search tree. */
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
        new DisplaySnapshot(null, 0L, Set.of(), Set.of(), Map.of(), false);
    private final Context context;
    private final long generation;
    private final Set<String> selectedPoints;
    private final Set<String> activePoints;
    private final Map<String, Integer> visits;
    private final boolean cancellationPending;

    private DisplaySnapshot(Context context, long generation, Set<String> selectedPoints,
        Set<String> activePoints, Map<String, Integer> visits, boolean cancellationPending) {
      this.context = context;
      this.generation = generation;
      this.selectedPoints = Collections.unmodifiableSet(new LinkedHashSet<>(selectedPoints));
      this.activePoints = Collections.unmodifiableSet(new LinkedHashSet<>(activePoints));
      this.visits = Collections.unmodifiableMap(new LinkedHashMap<>(visits));
      this.cancellationPending = cancellationPending;
    }

    public Context context() { return context; }
    public long generation() { return generation; }
    public Set<String> selectedPoints() { return selectedPoints; }
    public Set<String> activePoints() { return activePoints; }
    public Map<String, Integer> visits() { return visits; }
    public int targetVisits() { return context == null ? 0 : context.parameters.targetVisits(); }
    public boolean active() { return !activePoints.isEmpty() || cancellationPending; }
    public boolean cancellationPending() { return cancellationPending; }
  }

  private final TimeoutScheduler timeoutScheduler;
  private final Runnable displayChanged;
  private final LinkedHashSet<String> selectedPoints = new LinkedHashSet<>();
  private final LinkedHashSet<String> activePoints = new LinkedHashSet<>();
  private final LinkedHashMap<String, Integer> visits = new LinkedHashMap<>();
  private final LinkedHashMap<String, Cancellable> timeouts = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> timeoutTokens = new LinkedHashMap<>();
  private Set<String> installedPoints = Set.of();
  private Context context;
  private long generation;
  private long updateRevision;
  private long timeoutSequence;
  private boolean cancellationPending;
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

  synchronized AddResult addPoint(String coordinate, Context requestedContext,
      java.util.function.BooleanSupplier validator) {
    Objects.requireNonNull(requestedContext, "requestedContext");
    String point = normalizeCoordinate(coordinate, requestedContext);
    if (point == null) return AddResult.ILLEGAL;
    if (context != null && !context.matches(requestedContext)) return AddResult.CONTEXT_MISMATCH;
    if (selectedPoints.contains(point)) return AddResult.DUPLICATE;
    if (!requestedContext.engine.canStartMoveFocus()) return AddResult.LEASE_UNAVAILABLE;
    if (!isAvailablePoint(point, requestedContext)) return AddResult.ILLEGAL;
    Context acceptedContext = context == null ? requestedContext : context;
    List<MoveData> live = acceptedContext.engine.beginMoveFocus(this, acceptedContext,
        () -> isAvailablePoint(point, acceptedContext)
            && (validator == null || validator.getAsBoolean()));
    if (live == null) return AddResult.LEASE_UNAVAILABLE;
    if (context == null) {
      context = acceptedContext;
      generation++;
    }
    selectedPoints.add(point);
    activePoints.add(point);
    visits.put(point, 0);
    observeProgress(live);
    if (activePoints.contains(point)) scheduleTimeout(point);
    if (!sendUpdate(false)) {
      selectedPoints.remove(point);
      visits.remove(point);
      publishSnapshot();
      return AddResult.LEASE_UNAVAILABLE;
    }
    return AddResult.ADDED;
  }

  public synchronized boolean removePoint(String coordinate) {
    if (context == null) return false;
    String point = normalizeCoordinate(coordinate, context);
    if (point == null || !selectedPoints.remove(point)) return false;
    activePoints.remove(point);
    visits.remove(point);
    cancelTimeout(point);
    // Hide attention immediately; the installed set remains evidence until its boundary closes.
    publishSnapshot();
    sendUpdate(true);
    return true;
  }

  public synchronized void clear() {
    if (context == null) return;
    selectedPoints.clear();
    activePoints.clear();
    visits.clear();
    cancelTimeouts();
    publishSnapshot();
    sendUpdate(true);
  }

  public synchronized void contextChanged(Context currentContext) {
    if (context == null || (currentContext != null && context.matches(currentContext))) return;
    Context retired = context;
    clearContext();
    retired.engine.retireMoveFocus(this, retired);
  }

  public DisplaySnapshot snapshot() { return snapshot; }

  synchronized void onFocusAnalysis(Context source, List<MoveData> moves) {
    if (context != source) return;
    boolean completed = observeProgress(moves);
    if (completed) sendUpdate(false);
    else publishSnapshot();
  }

  private boolean observeProgress(List<MoveData> moves) {
    boolean completed = false;
    for (MoveData move : moves) {
      String point = move.coordinate;
      if (!selectedPoints.contains(point)) continue;
      int previous = visits.getOrDefault(point, 0);
      visits.put(point, Math.max(previous, move.playouts));
      if (!activePoints.contains(point)) continue;
      if (move.playouts >= context.parameters.targetVisits()) {
        activePoints.remove(point);
        cancelTimeout(point);
        completed = true;
      } else if (move.playouts > previous) {
        scheduleTimeout(point);
      }
    }
    return completed;
  }

  synchronized void onFocusUpdateConfirmed(Context source, long revision, Set<String> points) {
    if (context != source || revision != updateRevision) return;
    installedPoints = Set.copyOf(points);
    cancellationPending = !activePoints.containsAll(installedPoints);
    publishSnapshot();
  }

  synchronized void onFocusStopping(Context source, long revision, boolean gainPending) {
    if (context != source || revision != updateRevision) return;
    activePoints.clear();
    cancelTimeouts();
    cancellationPending = gainPending;
    publishSnapshot();
  }

  synchronized void onFocusStopped(Context source, boolean retireContext) {
    if (context != source) return;
    if (retireContext) {
      clearContext();
      return;
    }
    activePoints.clear();
    installedPoints = Set.of();
    cancellationPending = false;
    updateRevision++;
    cancelTimeouts();
    publishSnapshot();
  }

  private boolean sendUpdate(boolean retireUnconsumed) {
    Context target = context;
    if (target == null) return false;
    long revision = ++updateRevision;
    cancellationPending = cancellationPending || !activePoints.containsAll(installedPoints);
    publishSnapshot();
    boolean accepted = target.engine.updateMoveFocus(
        this, target, Set.copyOf(activePoints), revision, retireUnconsumed);
    if (!accepted && context == target) {
      activePoints.clear();
      cancelTimeouts();
      // A failed update is not a cancellation acknowledgement. Engine retirement settles it.
      cancellationPending = cancellationPending || !installedPoints.isEmpty();
      publishSnapshot();
    }
    return accepted;
  }

  private void scheduleTimeout(String point) {
    cancelTimeout(point);
    long token = ++timeoutSequence;
    timeoutTokens.put(point, token);
    Context source = context;
    timeouts.put(point, timeoutScheduler.schedule(PROGRESS_TIMEOUT_MILLIS,
        () -> onTimeout(source, point, token)));
  }

  private synchronized void onTimeout(Context source, String point, long token) {
    if (context != source || !Objects.equals(timeoutTokens.get(point), token)
        || !activePoints.remove(point)) return;
    cancelTimeout(point);
    sendUpdate(true);
  }

  private void cancelTimeout(String point) {
    timeoutTokens.remove(point);
    Cancellable timeout = timeouts.remove(point);
    if (timeout != null) timeout.cancel();
  }

  private void cancelTimeouts() {
    for (Cancellable timeout : timeouts.values()) timeout.cancel();
    timeouts.clear();
    timeoutTokens.clear();
  }

  private void clearContext() {
    cancelTimeouts();
    selectedPoints.clear();
    activePoints.clear();
    visits.clear();
    installedPoints = Set.of();
    cancellationPending = false;
    context = null;
    generation++;
    updateRevision++;
    publishSnapshot();
  }

  private void publishSnapshot() {
    snapshot = context == null ? DisplaySnapshot.EMPTY
        : new DisplaySnapshot(context, generation, selectedPoints, activePoints, visits,
            cancellationPending);
    displayChanged.run();
  }

  private static boolean isAvailablePoint(String point, Context context) {
    if (!(context.displayNodeIdentity instanceof BoardHistoryNode)) return false;
    BoardHistoryNode node = (BoardHistoryNode) context.displayNodeIdentity;
    char column = point.charAt(0);
    int x = column - 'A' - (column > 'I' ? 1 : 0);
    int y = context.boardHeight - Integer.parseInt(point.substring(1));
    Stone[] stones = node.getData().stones;
    int index = x * context.boardHeight + y;
    if (index >= stones.length || stones[index] != Stone.EMPTY) return false;
    if (Lizzie.frame != null && (Lizzie.frame.isKeepingForce || LizzieFrame.isKeepForcing)) {
      String allow = LizzieFrame.allowcoords;
      if (allow != null && !allow.isEmpty()) return containsCoordinate(allow, point);
      String avoid = LizzieFrame.avoidcoords;
      if (avoid != null && !avoid.isEmpty()) return !containsCoordinate(avoid, point);
    }
    return true;
  }

  private static boolean containsCoordinate(String coordinates, String point) {
    for (String coordinate : coordinates.split(",")) {
      if (point.equalsIgnoreCase(coordinate.trim())) return true;
    }
    return false;
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
