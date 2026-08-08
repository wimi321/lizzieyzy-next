package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Narrow ReadBoard GMA session module — the single logical owner of one ReadBoard GMA session's
 * combined-barrier correctness.
 *
 * <p>Each session owns a non-reusable opaque identity and exactly one discriminated state:
 *
 * <pre>
 * GmaInFlight(authorization, authoritativeRestoreIntent)
 *   -> Terminal(Succeeded)                                  // accepted PLAYED
 *   -> RestoringExact(capturedExactOperation)               // isolation terminal
 *      -> RestoringRuntime(capturedRuntimeSnapshot)         // retired runtime restore only
 *      -> RestoringExact(latestDeferredExactOperation)      // delayed authority, latest wins
 * Terminal(Succeeded | Failed(firstFailure) | CancelledNoEffect)
 * </pre>
 *
 * <p>Retirement is an orthogonal sticky fact ({@link #retired()}), not a family of retired phases:
 * active physical work keeps its phase and converges to its terminal outcome. Retirement atomically
 * revokes the helper-facing {@link HelperCapability}; it never revokes the already started
 * session-owned operation capabilities.
 *
 * <p>Events arrive only through session-bound handles/capabilities that bind the session identity,
 * the expected phase, the issuance attempt, and the captured engine incarnation. Stale, duplicate,
 * late, and ABA events are absorbed with zero state change and zero effects. Legal transitions emit
 * a private, immutable, ordered effect batch that the session dispatches to its narrow {@link
 * Ports} in order; the first transition into {@link Terminal} publishes the session terminal,
 * applies the outcome handling (failure handling or, only for a non-retired success, the normal
 * continuation), and requests the captured reservation release exactly once. Explicit retry
 * requires a new session with a new opaque identity.
 *
 * <p>ReadBoard remains the board/retirement adapter and Leelaz remains the GTP protocol adapter
 * (queue, response binding, timeout, late-response absorption, runtime ACK aggregation, and
 * reservation mechanics); this module owns neither. ExactSnapshotEngineRestore keeps its exact
 * restore ownership and only reports its aggregate result through the typed seam. Raw GTP text and
 * per-command mechanics never cross the seam.
 */
public final class ReadBoardGmaSession {
  /** The five mutually exclusive discriminated state variants. */
  public sealed interface State
      permits Preparing, GmaInFlight, RestoringExact, RestoringRuntime, Terminal {
    // Marker: variants are declared below.
  }

  /** No physical engine side effect yet; helper retirement cancels the session. */
  public record Preparing() implements State {}

  /**
   * The physical GMA request is admitted. The logical authorization and the latest-wins
   * authoritative restore intent exist before the engine command is sent; retirement freezes the
   * intent and stops new captures.
   */
  public record GmaInFlight(GmaAuthorization authorization, Object authoritativeRestoreIntent)
      implements State {}

  /**
   * The exact snapshot restore participant owns the phase; it reports exactly one aggregate result
   * through its capability.
   */
  public record RestoringExact(CapturedExactOperation capturedExactOperation) implements State {}

  /**
   * The runtime parameter restore participant owns the phase; it reports exactly one aggregate
   * result through its capability.
   */
  public record RestoringRuntime(RuntimeSnapshot capturedRuntimeSnapshot) implements State {}

  /**
   * Session terminal — absorbing. Duplicate, late, stale, and ABA events never leave it and never
   * produce effects. {@code firstFailure} is non-null exactly for {@link SessionOutcome#FAILED}.
   */
  public record Terminal(SessionOutcome outcome, ParticipantFailure firstFailure) implements State {
    public Terminal {
      Objects.requireNonNull(outcome, "outcome");
      if (outcome == SessionOutcome.FAILED) {
        Objects.requireNonNull(firstFailure, "firstFailure");
      } else if (firstFailure != null) {
        throw new IllegalArgumentException("firstFailure only for FAILED");
      }
    }
  }

  /** Closed session terminal outcomes. */
  public enum SessionOutcome {
    /** Both participants confirmed; combined restore succeeded. */
    SUCCEEDED,
    /** The first matching participant failure locked the session. */
    FAILED,
    /** Helper retirement in {@link Preparing} with zero physical side effect. */
    CANCELLED_NO_EFFECT
  }

  /** Closed semantic GMA terminal variants; raw GTP text never crosses the seam. */
  public enum GmaTerminal {
    /** The engine produced a valid final play that the helper consumed. */
    PLAYED,
    /** The engine ended the request with pass. */
    PASS,
    /** The engine ended the request with resign. */
    RESIGN,
    /** The request ended with a GTP error. */
    REQUEST_ERROR,
    /** The request ended without a response before the timeout. */
    REQUEST_TIMEOUT
  }

  /**
   * Stable participant failure categories; the session never branches on raw adapter exceptions.
   */
  public enum FailureCategory {
    /** The participant start port rejected the operation synchronously. */
    START_REJECTED,
    /** Sending a restore command failed at the physical layer. */
    SEND_FAILED,
    /** A restore command received a GTP error response. */
    GTP_ERROR,
    /** A required confirmation did not arrive before the timeout. */
    TIMEOUT,
    /** A captured tail command was rejected by engine arbitration. */
    TAIL_REJECTED,
    /** The captured engine process terminated before confirmation. */
    PROCESS_TERMINATED,
    /** A stale admission was rejected before any physical side effect. */
    ADMISSION_STALE
  }

  /**
   * A participant failure: stable category, the captured engine incarnation it belongs to, and an
   * optional human detail. The session locks the first matching failure and never overwrites it.
   */
  public record ParticipantFailure(
      FailureCategory category, Object engineIncarnation, String detail) {
    public ParticipantFailure {
      Objects.requireNonNull(category, "category");
      Objects.requireNonNull(engineIncarnation, "engineIncarnation");
    }
  }

  /**
   * Typed synchronous participant admission failure. Adapters use this only when no physical
   * command has been issued, so stale admission can fail closed without quarantining a replacement
   * engine incarnation.
   */
  static final class ParticipantStartFailure extends IllegalStateException {
    private final FailureCategory category;

    ParticipantStartFailure(FailureCategory category, String detail) {
      super(detail);
      this.category = Objects.requireNonNull(category, "category");
    }

    FailureCategory category() {
      return category;
    }
  }

  /** Closed aggregate participant result delivered by the protocol adapter. */
  public sealed interface ParticipantResult
      permits ParticipantResult.Succeeded, ParticipantResult.Failed {
    /** The participant reached its full confirmation boundary. */
    record Succeeded() implements ParticipantResult {}

    /** The participant failed before confirmation; the session treats it as a matching failure. */
    record Failed(ParticipantFailure failure) implements ParticipantResult {
      public Failed {
        Objects.requireNonNull(failure, "failure");
      }
    }
  }

  /**
   * The exact operation captured when the phase started: its one-shot capability and the frozen
   * authoritative restore intent.
   */
  public record CapturedExactOperation(
      ExactParticipantCapability capability, Object restoreIntent) {}

  /**
   * Session-owned immutable runtime restore snapshot: the parameters captured at GMA admission. The
   * session treats the contents as opaque; the Leelaz adapter that captured them reads them back
   * through {@link #parameters()} and maps them to its runtime restore commands and ACK
   * aggregation. A continuing active session retains its runtime settings between hands; after
   * retirement, an empty snapshot means exact success alone completes the session.
   */
  public static final class RuntimeSnapshot {
    private static final RuntimeSnapshot EMPTY = new RuntimeSnapshot(List.of());

    private final List<?> parameters;

    private RuntimeSnapshot(Collection<?> parameters) {
      this.parameters = List.copyOf(parameters);
    }

    /** Builds a snapshot from the adapter's captured runtime parameters. */
    public static RuntimeSnapshot of(Collection<?> parameters) {
      Objects.requireNonNull(parameters, "parameters");
      return parameters.isEmpty() ? EMPTY : new RuntimeSnapshot(parameters);
    }

    /** The empty snapshot: this session captured no runtime parameters to restore. */
    public static RuntimeSnapshot empty() {
      return EMPTY;
    }

    /**
     * Immutable view of the captured runtime parameters, in capture order. Only the adapter that
     * captured them may consume them; the session never reads the contents.
     */
    public List<?> parameters() {
      return parameters;
    }

    /** Whether this session has no runtime parameter restore work. */
    public boolean isEmpty() {
      return parameters.isEmpty();
    }
  }

  /**
   * Logical go-ahead of the admitted GMA request. It exists only while the state is {@link
   * GmaInFlight}; invalidation is sticky and effect-free — the physical request keeps converging
   * and the terminal is still consumed exactly once.
   */
  public static final class GmaAuthorization {
    /** Volatile so a state() reader can observe invalidation without the session lock. */
    private volatile boolean invalidated;

    private GmaAuthorization() {}

    private void invalidate() {
      invalidated = true;
    }

    /** Whether the helper invalidated the logical authorization. */
    public boolean invalidated() {
      return invalidated;
    }
  }

  /**
   * Helper-facing adapter capability. Issued at session creation; retirement atomically revokes it
   * (every later helper event is absorbed with zero effects). It is the only route for helper
   * events: GMA admission, latest-wins restore intent updates, authorization invalidation, and
   * retirement.
   */
  public static final class HelperCapability {
    private final ReadBoardGmaSession session;

    private HelperCapability(ReadBoardGmaSession session) {
      this.session = session;
    }
  }

  /**
   * One-shot capability bound to the admitted GMA request; only valid while the state is {@link
   * GmaInFlight}. It binds the session identity, the captured engine incarnation, and the issuance
   * attempt; the adapter can neither forge nor replace these fields.
   */
  public static final class GmaTerminalCapability {
    private final ReadBoardGmaSession session;
    private final Object engineIncarnation;
    private final int attempt;

    private GmaTerminalCapability(
        ReadBoardGmaSession session, Object engineIncarnation, int attempt) {
      this.session = session;
      this.engineIncarnation = engineIncarnation;
      this.attempt = attempt;
    }

    /** The issuance attempt ordinal for this phase; immutable and unforgeable. */
    public int attempt() {
      return attempt;
    }
  }

  /**
   * One-shot capability bound to the exact snapshot restore participant; only valid while the state
   * is {@link RestoringExact}. Issued for an isolation terminal or a deferred authoritative restore;
   * never revoked by helper retirement.
   */
  public static final class ExactParticipantCapability {
    private final ReadBoardGmaSession session;
    private final Object engineIncarnation;
    private final int attempt;

    private ExactParticipantCapability(
        ReadBoardGmaSession session, Object engineIncarnation, int attempt) {
      this.session = session;
      this.engineIncarnation = engineIncarnation;
      this.attempt = attempt;
    }

    /** The issuance attempt ordinal for this phase; immutable and unforgeable. */
    public int attempt() {
      return attempt;
    }
  }

  /**
   * One-shot capability bound to the runtime parameter restore participant; only valid while the
   * state is {@link RestoringRuntime}. Issued by the module after exact success; never revoked by
   * helper retirement.
   */
  public static final class RuntimeParticipantCapability {
    private final ReadBoardGmaSession session;
    private final Object engineIncarnation;
    private final int attempt;

    private RuntimeParticipantCapability(
        ReadBoardGmaSession session, Object engineIncarnation, int attempt) {
      this.session = session;
      this.engineIncarnation = engineIncarnation;
      this.attempt = attempt;
    }

    /** The issuance attempt ordinal for this phase; immutable and unforgeable. */
    public int attempt() {
      return attempt;
    }
  }

  /**
   * Non-reusable reservation release capability captured at session creation and bound to the
   * session identity, the captured engine incarnation, and the reservation owner. The release port
   * receives it at most once and must not look up the current global engine or a session id to
   * release the reservation.
   */
  public static final class ReservationReleaseCapability {
    private final ReadBoardGmaSession session;
    private final Object engineIncarnation;
    private final Object reservationOwner;

    private ReservationReleaseCapability(
        ReadBoardGmaSession session, Object engineIncarnation, Object reservationOwner) {
      this.session = session;
      this.engineIncarnation = engineIncarnation;
      this.reservationOwner = reservationOwner;
    }

    /** The reservation owner captured at session creation; the release port validates it. */
    public Object reservationOwner() {
      return reservationOwner;
    }

    /** The engine incarnation captured at session creation. */
    public Object engineIncarnation() {
      return engineIncarnation;
    }
  }

  /**
   * Narrow ports the session dispatches its ordered effects to. Callers only report events; they
   * never execute, reorder, duplicate, or discard effects.
   */
  public interface Ports {
    /**
     * Starts the exact snapshot restore participant for the frozen {@code restoreIntent}. Throwing
     * a {@link RuntimeException} is the synchronous rejection contract: the session converts it
     * into a typed {@link FailureCategory#START_REJECTED} participant failure and fail-closes; it
     * never hands the rejection back to the caller.
     */
    void startExact(ExactParticipantCapability capability, Object restoreIntent);

    /**
     * Starts the runtime parameter restore participant for the captured snapshot. Throwing a {@link
     * RuntimeException} is the synchronous rejection contract, converted like {@link
     * #startExact(ExactParticipantCapability, Object)}.
     */
    void startRuntime(RuntimeParticipantCapability capability, RuntimeSnapshot runtimeSnapshot);

    /** Publishes the session terminal; delivered exactly once per session. */
    void publishTerminal(Terminal terminal);

    /**
     * Applies the fail-closed handling for the first locked participant failure. Quarantine policy
     * stays adapter-owned; the session only fixes which failure is first.
     */
    void handleFailure(ParticipantFailure firstFailure);

    /**
     * Grants normal continuation; emitted only on success while the helper has not retired. A
     * retired session that still succeeds only completes protocol convergence and releases.
     */
    void continueNormal();

    /** Requests the captured reservation release; delivered exactly once per session. */
    void requestReservationRelease(ReservationReleaseCapability capability);
  }

  private final Object engineIncarnation;
  private final Ports ports;
  private final Object lock = new Object();
  private final HelperCapability helperCapability;
  private final ReservationReleaseCapability reservationReleaseCapability;

  private State state;
  private boolean retired;
  private int nextAttempt;
  private int gmaTerminalAttempt = -1;
  private int exactAttempt = -1;
  private int runtimeAttempt = -1;
  private RuntimeSnapshot runtimeSnapshot = RuntimeSnapshot.empty();
  private Object deferredExactRestoreIntent;
  private boolean runtimeRestoreCompleted;

  private ReadBoardGmaSession(Object engineIncarnation, Object reservationOwner, Ports ports) {
    this.engineIncarnation = engineIncarnation;
    this.ports = ports;
    this.state = new Preparing();
    this.helperCapability = new HelperCapability(this);
    this.reservationReleaseCapability =
        new ReservationReleaseCapability(this, engineIncarnation, reservationOwner);
  }

  /**
   * Creates a new session with a fresh opaque identity. {@code engineIncarnation} is the captured
   * engine process incarnation all capabilities bind to; {@code reservationOwner} is the engine
   * reservation owner captured for the exactly-once release.
   */
  public static ReadBoardGmaSession create(
      Object engineIncarnation, Object reservationOwner, Ports ports) {
    Objects.requireNonNull(engineIncarnation, "engineIncarnation");
    Objects.requireNonNull(reservationOwner, "reservationOwner");
    Objects.requireNonNull(ports, "ports");
    return new ReadBoardGmaSession(engineIncarnation, reservationOwner, ports);
  }

  /** The canonical discriminated state. */
  public State state() {
    synchronized (lock) {
      return state;
    }
  }

  /** The orthogonal sticky retirement fact. */
  public boolean retired() {
    synchronized (lock) {
      return retired;
    }
  }

  /** The helper-facing capability; revoked atomically by {@link #retire(HelperCapability)}. */
  public HelperCapability helperCapability() {
    return helperCapability;
  }

  /**
   * The captured engine incarnation all capabilities of this session bind to. Adapters use it when
   * building {@link ParticipantFailure} payloads so that foreign-incarnation failures are absorbed
   * by the admission guard instead of locking this session.
   */
  public Object engineIncarnation() {
    return engineIncarnation;
  }

  /** The captured reservation release authority used by the terminal effect. */
  public ReservationReleaseCapability reservationReleaseCapability() {
    return reservationReleaseCapability;
  }

  /**
   * Admits the physical GMA request: transitions {@link Preparing} to {@link GmaInFlight} and
   * issues the one-shot {@link GmaTerminalCapability} for Leelaz response binding. The caller must
   * already hold the authoritative restore intent (a null intent fails fast — the intent exists
   * before the engine command is sent). Returns {@code null} when the event is stale: helper
   * revoked, already admitted, or session terminal.
   */
  public GmaTerminalCapability admitGma(
      HelperCapability helper, Object restoreIntent, RuntimeSnapshot runtimeSnapshot) {
    Objects.requireNonNull(helper, "helper");
    Objects.requireNonNull(restoreIntent, "restoreIntent");
    Objects.requireNonNull(runtimeSnapshot, "runtimeSnapshot");
    synchronized (lock) {
      if (!isHelperCurrent(helper) || !(state instanceof Preparing)) {
        return null;
      }
      this.runtimeSnapshot = runtimeSnapshot;
      state = new GmaInFlight(new GmaAuthorization(), restoreIntent);
      GmaTerminalCapability capability =
          new GmaTerminalCapability(this, engineIncarnation, nextAttempt++);
      gmaTerminalAttempt = capability.attempt();
      return capability;
    }
  }

  /** Updates the latest-wins authoritative restore intent while the GMA request is in flight. */
  public void updateRestoreIntent(HelperCapability helper, Object restoreIntent) {
    Objects.requireNonNull(helper, "helper");
    Objects.requireNonNull(restoreIntent, "restoreIntent");
    List<Effect> effects;
    synchronized (lock) {
      effects = onUpdateRestoreIntent(helper, restoreIntent);
    }
    dispatch(effects);
  }

  private List<Effect> onUpdateRestoreIntent(HelperCapability helper, Object restoreIntent) {
    if (!isHelperCurrent(helper) || !(state instanceof GmaInFlight gma)) {
      return List.of();
    }
    state = new GmaInFlight(gma.authorization(), restoreIntent);
    return List.of();
  }

  /**
   * Invalidates the logical GMA authorization (sticky, effect-free). The physical request keeps
   * converging; the terminal is still consumed and the session still reaches its combined terminal.
   */
  public void invalidateAuthorization(HelperCapability helper) {
    Objects.requireNonNull(helper, "helper");
    List<Effect> effects;
    synchronized (lock) {
      effects = onInvalidateAuthorization(helper);
    }
    dispatch(effects);
  }

  private List<Effect> onInvalidateAuthorization(HelperCapability helper) {
    if (!isHelperCurrent(helper) || !(state instanceof GmaInFlight gma)) {
      return List.of();
    }
    gma.authorization().invalidate();
    return List.of();
  }

  /**
   * Retires the ReadBoard helper; idempotent. In {@link Preparing} (zero physical side effect) the
   * session cancels to {@link SessionOutcome#CANCELLED_NO_EFFECT}; in every active physical phase
   * it only sets the sticky fact and keeps converging.
   */
  public void retire(HelperCapability helper) {
    Objects.requireNonNull(helper, "helper");
    List<Effect> effects;
    synchronized (lock) {
      effects = onRetire(helper);
    }
    dispatch(effects);
  }

  private List<Effect> onRetire(HelperCapability helper) {
    if (helper.session != this || retired) {
      return List.of();
    }
    retired = true;
    if (state instanceof GmaInFlight gma) {
      // Retirement immediately revokes the helper's logical GMA authorization.
      gma.authorization().invalidate();
      return List.of();
    }
    if (state instanceof Preparing) {
      return terminalEffects(new Terminal(SessionOutcome.CANCELLED_NO_EFFECT, null));
    }
    return List.of();
  }

  /**
   * Reports the consumed GMA terminal through its one-shot capability. An accepted {@link
   * GmaTerminal#PLAYED} completes directly; isolation terminals transition to {@link
   * RestoringExact} and emit the exact start effect.
   */
  public void consumeGmaTerminal(GmaTerminalCapability capability, GmaTerminal terminal) {
    consumeGmaTerminal(capability, terminal, null);
  }

  /**
   * Reports a terminal together with the final authoritative restore intent captured by the
   * physical terminal owner. The terminal capability remains valid after helper retirement, so a
   * late physical PLAYED result can restore the post-play position without re-authorizing helper
   * activity.
   */
  public void consumeGmaTerminal(
      GmaTerminalCapability capability, GmaTerminal terminal, Object finalRestoreIntent) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(terminal, "terminal");
    List<Effect> effects;
    synchronized (lock) {
      effects = onGmaTerminal(capability, terminal, finalRestoreIntent);
    }
    dispatch(effects);
  }

  /**
   * Fails admitted physical work when the captured engine process terminates. The terminal
   * capability keeps transport death bound to the original session and incarnation; duplicate,
   * stale, and post-terminal notifications are absorbed.
   */
  public boolean failEngineProcess(GmaTerminalCapability capability, String detail) {
    Objects.requireNonNull(capability, "capability");
    List<Effect> effects;
    synchronized (lock) {
      if (!isCapabilityCurrent(capability)
          || capability.attempt != gmaTerminalAttempt
          || state instanceof Preparing
          || state instanceof Terminal) {
        return false;
      }
      if (state instanceof GmaInFlight gma) {
        gma.authorization().invalidate();
      }
      ParticipantFailure failure =
          new ParticipantFailure(FailureCategory.PROCESS_TERMINATED, engineIncarnation, detail);
      effects = terminalEffects(new Terminal(SessionOutcome.FAILED, failure));
    }
    dispatch(effects);
    return true;
  }

  private List<Effect> onGmaTerminal(
      GmaTerminalCapability capability, GmaTerminal terminal, Object finalRestoreIntent) {
    if (!isCapabilityCurrent(capability)
        || capability.attempt != gmaTerminalAttempt
        || !(state instanceof GmaInFlight gma)) {
      return List.of();
    }
    gma.authorization().invalidate();
    if (terminal == GmaTerminal.PLAYED) {
      return retired && !runtimeSnapshot.isEmpty()
          ? startRuntimeRestore()
          : terminalEffects(new Terminal(SessionOutcome.SUCCEEDED, null));
    }
    Object restoreIntent =
        finalRestoreIntent == null ? gma.authoritativeRestoreIntent() : finalRestoreIntent;
    ExactParticipantCapability exactCapability =
        new ExactParticipantCapability(this, engineIncarnation, nextAttempt++);
    exactAttempt = exactCapability.attempt();
    state = new RestoringExact(new CapturedExactOperation(exactCapability, restoreIntent));
    return List.of(new StartExact(exactCapability, restoreIntent));
  }

  /**
   * Records the latest authoritative exact restore requested while participant convergence is in
   * progress. The physical terminal capability binds the request to this session's admitted engine
   * incarnation; stale or post-terminal requests are rejected.
   */
  public boolean deferExactRestore(
      GmaTerminalCapability capability, Object restoreIntent) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(restoreIntent, "restoreIntent");
    synchronized (lock) {
      if (!isCapabilityCurrent(capability)
          || capability.attempt != gmaTerminalAttempt
          || (!(state instanceof RestoringExact) && !(state instanceof RestoringRuntime))) {
        return false;
      }
      deferredExactRestoreIntent = restoreIntent;
      return true;
    }
  }

  /** Reports the exact participant aggregate result through its one-shot capability. */
  public void completeExact(ExactParticipantCapability capability, ParticipantResult result) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(result, "result");
    List<Effect> effects;
    synchronized (lock) {
      effects = onCompleteExact(capability, result);
    }
    dispatch(effects);
  }

  private List<Effect> onCompleteExact(
      ExactParticipantCapability capability, ParticipantResult result) {
    if (!isCapabilityCurrent(capability)
        || capability.attempt != exactAttempt
        || !(state instanceof RestoringExact)) {
      return List.of();
    }
    if (result instanceof ParticipantResult.Failed failed
        && failed.failure().engineIncarnation() != engineIncarnation) {
      return List.of();
    }
    if (result instanceof ParticipantResult.Failed failed) {
      return terminalEffects(new Terminal(SessionOutcome.FAILED, failed.failure()));
    }
    if (deferredExactRestoreIntent != null) {
      return startDeferredExactRestore();
    }
    if (runtimeRestoreCompleted || !retired || runtimeSnapshot.isEmpty()) {
      return terminalEffects(new Terminal(SessionOutcome.SUCCEEDED, null));
    }
    return startRuntimeRestore();
  }

  private List<Effect> startRuntimeRestore() {
    RuntimeParticipantCapability runtimeCapability =
        new RuntimeParticipantCapability(this, engineIncarnation, nextAttempt++);
    runtimeAttempt = runtimeCapability.attempt();
    state = new RestoringRuntime(runtimeSnapshot);
    return List.of(new StartRuntime(runtimeCapability, runtimeSnapshot));
  }

  /** Reports the runtime participant aggregate result through its one-shot capability. */
  public void completeRuntime(RuntimeParticipantCapability capability, ParticipantResult result) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(result, "result");
    List<Effect> effects;
    synchronized (lock) {
      effects = onCompleteRuntime(capability, result);
    }
    dispatch(effects);
  }

  private List<Effect> onCompleteRuntime(
      RuntimeParticipantCapability capability, ParticipantResult result) {
    if (!isCapabilityCurrent(capability)
        || capability.attempt != runtimeAttempt
        || !(state instanceof RestoringRuntime)) {
      return List.of();
    }
    if (result instanceof ParticipantResult.Failed failed
        && failed.failure().engineIncarnation() != engineIncarnation) {
      return List.of();
    }
    if (result instanceof ParticipantResult.Failed failed) {
      return terminalEffects(new Terminal(SessionOutcome.FAILED, failed.failure()));
    }
    runtimeRestoreCompleted = true;
    if (deferredExactRestoreIntent != null) {
      return startDeferredExactRestore();
    }
    return terminalEffects(new Terminal(SessionOutcome.SUCCEEDED, null));
  }

  private List<Effect> startDeferredExactRestore() {
    Object restoreIntent = deferredExactRestoreIntent;
    deferredExactRestoreIntent = null;
    ExactParticipantCapability exactCapability =
        new ExactParticipantCapability(this, engineIncarnation, nextAttempt++);
    exactAttempt = exactCapability.attempt();
    state = new RestoringExact(new CapturedExactOperation(exactCapability, restoreIntent));
    return List.of(new StartExact(exactCapability, restoreIntent));
  }

  private boolean isHelperCurrent(HelperCapability helper) {
    return helper.session == this && !retired;
  }

  private boolean isCapabilityCurrent(GmaTerminalCapability capability) {
    return capability.session == this && capability.engineIncarnation == engineIncarnation;
  }

  private boolean isCapabilityCurrent(ExactParticipantCapability capability) {
    return capability.session == this && capability.engineIncarnation == engineIncarnation;
  }

  private boolean isCapabilityCurrent(RuntimeParticipantCapability capability) {
    return capability.session == this && capability.engineIncarnation == engineIncarnation;
  }

  /**
   * First transition into {@link Terminal}: builds the exactly-once ordered effect batch. The batch
   * is fixed atomically by the transition — a retirement that lands after the transition is
   * absorbed and cannot suppress a continuation already granted by it.
   */
  private List<Effect> terminalEffects(Terminal terminal) {
    state = terminal;
    List<Effect> effects = new ArrayList<>();
    if (terminal.outcome() == SessionOutcome.FAILED) {
      // Quarantine before publication so a concurrent authority update cannot start legacy restore
      // work against a failed session.
      effects.add(new HandleFailure(terminal.firstFailure()));
    }
    effects.add(new PublishTerminal(terminal));
    if (terminal.outcome() == SessionOutcome.SUCCEEDED && !retired) {
      effects.add(new ContinueNormal());
    }
    effects.add(new RequestRelease(reservationReleaseCapability));
    return List.copyOf(effects);
  }

  private ParticipantFailure startRejected(RuntimeException cause) {
    FailureCategory category =
        cause instanceof ParticipantStartFailure failure
            ? failure.category()
            : FailureCategory.START_REJECTED;
    return new ParticipantFailure(category, engineIncarnation, cause.getMessage());
  }

  /**
   * Applies the ordered effect batch to the narrow ports; participant-start rejection fail-closes.
   *
   * <p>If a start port both completes its participant synchronously and then throws, the session
   * has already advanced past the phase, so the rejection conversion is absorbed by the phase guard
   * and the synchronous completion stands; the start contract is: throw without completing, or
   * complete without throwing. Non-start port failures never suppress later effects — they are
   * rethrown to the caller after the whole batch was applied in order.
   */
  private void dispatch(List<Effect> effects) {
    RuntimeException firstPortFailure = null;
    for (Effect effect : effects) {
      try {
        effect.apply(ports);
      } catch (RuntimeException portFailure) {
        if (effect instanceof StartExact startExact) {
          try {
            completeExact(
                startExact.capability(), new ParticipantResult.Failed(startRejected(portFailure)));
          } catch (RuntimeException nestedFailure) {
            if (firstPortFailure == null) {
              firstPortFailure = nestedFailure;
            }
          }
        } else if (effect instanceof StartRuntime startRuntime) {
          try {
            completeRuntime(
                startRuntime.capability(),
                new ParticipantResult.Failed(startRejected(portFailure)));
          } catch (RuntimeException nestedFailure) {
            if (firstPortFailure == null) {
              firstPortFailure = nestedFailure;
            }
          }
        } else if (firstPortFailure == null) {
          firstPortFailure = portFailure;
        }
      }
    }
    if (firstPortFailure != null) {
      throw firstPortFailure;
    }
  }

  private sealed interface Effect
      permits StartExact,
          StartRuntime,
          PublishTerminal,
          HandleFailure,
          ContinueNormal,
          RequestRelease {
    void apply(Ports ports);
  }

  private record StartExact(ExactParticipantCapability capability, Object restoreIntent)
      implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.startExact(capability, restoreIntent);
    }
  }

  private record StartRuntime(
      RuntimeParticipantCapability capability, RuntimeSnapshot runtimeSnapshot) implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.startRuntime(capability, runtimeSnapshot);
    }
  }

  private record PublishTerminal(Terminal terminal) implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.publishTerminal(terminal);
    }
  }

  private record HandleFailure(ParticipantFailure firstFailure) implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.handleFailure(firstFailure);
    }
  }

  private record ContinueNormal() implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.continueNormal();
    }
  }

  private record RequestRelease(ReservationReleaseCapability capability) implements Effect {
    @Override
    public void apply(Ports ports) {
      ports.requestReservationRelease(capability);
    }
  }
}
