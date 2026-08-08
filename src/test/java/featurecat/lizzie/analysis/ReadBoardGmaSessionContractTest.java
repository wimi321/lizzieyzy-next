package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deterministic contract tests for the narrow ReadBoard GMA session module. They observe state
 * transitions and ordered effects only — never field counts, private flags, or source shape.
 */
class ReadBoardGmaSessionContractTest {
  private static final Object INCARNATION = new Object();

  @Test
  void sessionStartsInPreparingWithHelperAndReleaseCapabilities() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);

    assertInstanceOf(ReadBoardGmaSession.Preparing.class, session.state());
    assertFalse(session.retired());
    assertNotNull(session.helperCapability());
    assertTrue(ports.calls.isEmpty());
  }

  @Test
  void admitGmaTransitionsToGmaInFlightAndIssuesTerminalCapability() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object intent = new Object();

    ReadBoardGmaSession.GmaTerminalCapability capability =
        session.admitGma(
            session.helperCapability(), intent, ReadBoardGmaSession.RuntimeSnapshot.empty());

    ReadBoardGmaSession.GmaInFlight gma =
        assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state());
    assertSame(intent, gma.authoritativeRestoreIntent());
    assertFalse(gma.authorization().invalidated());
    assertEquals(0, capability.attempt());
    assertTrue(ports.calls.isEmpty());
  }

  @Test
  void admitGmaFailsFastWithoutRestoreIntentOrRuntimeSnapshot() {
    ReadBoardGmaSession session = createSession(new RecordingPorts());
    ReadBoardGmaSession.HelperCapability helper = session.helperCapability();

    assertThrows(
        NullPointerException.class,
        () -> session.admitGma(helper, null, ReadBoardGmaSession.RuntimeSnapshot.empty()));
    assertThrows(NullPointerException.class, () -> session.admitGma(helper, new Object(), null));
    assertInstanceOf(ReadBoardGmaSession.Preparing.class, session.state());
  }

  @Test
  void admitGmaIsAbsorbedAfterCancellationOrWhenAlreadyAdmitted() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.HelperCapability helper = session.helperCapability();
    Object intent = new Object();

    session.retire(helper);
    assertNull(session.admitGma(helper, intent, ReadBoardGmaSession.RuntimeSnapshot.empty()));
    assertEquals(
        ReadBoardGmaSession.SessionOutcome.CANCELLED_NO_EFFECT, terminalOf(session).outcome());

    ReadBoardGmaSession second = createSession(new RecordingPorts());
    ReadBoardGmaSession.HelperCapability secondHelper = second.helperCapability();
    assertNotNull(
        second.admitGma(secondHelper, intent, ReadBoardGmaSession.RuntimeSnapshot.empty()));
    assertNull(second.admitGma(secondHelper, intent, ReadBoardGmaSession.RuntimeSnapshot.empty()));
    assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, second.state());
  }

  @Test
  void updateRestoreIntentIsLatestWinsInsideGmaInFlightOnly() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.HelperCapability helper = session.helperCapability();
    Object firstIntent = new Object();
    Object latestIntent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(helper, firstIntent, ReadBoardGmaSession.RuntimeSnapshot.empty());

    session.updateRestoreIntent(helper, latestIntent);

    ReadBoardGmaSession.GmaInFlight gma =
        assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state());
    assertSame(latestIntent, gma.authoritativeRestoreIntent());
    assertTrue(ports.calls.isEmpty());

    // Retirement freezes helper updates, but the still-valid physical terminal capability can
    // provide the final authoritative post-terminal intent without re-authorizing the helper.
    session.retire(helper);
    session.updateRestoreIntent(helper, new Object());
    assertSame(
        latestIntent,
        assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state())
            .authoritativeRestoreIntent());

    Object finalTerminalIntent = new Object();
    session.consumeGmaTerminal(
        terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR, finalTerminalIntent);
    assertSame(
        finalTerminalIntent,
        assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state())
            .capturedExactOperation()
            .restoreIntent());
  }

  @Test
  void invalidateAuthorizationIsStickyAndEffectFree() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.HelperCapability helper = session.helperCapability();
    Object intent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(helper, intent, nonEmptySnapshot());

    session.invalidateAuthorization(helper);
    session.invalidateAuthorization(helper);

    ReadBoardGmaSession.GmaInFlight gma =
        assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state());
    assertTrue(gma.authorization().invalidated());
    assertTrue(ports.calls.isEmpty());

    // The physical request keeps converging; an active session's exact success reaches the
    // terminal directly — the runtime phase is never entered — while success still publishes,
    // continues, and releases.
    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertTrue(
        ports.runtimeStarts.isEmpty(), "an active exact success must not start runtime restore");
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(1, ports.continuations);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
  }

  @ParameterizedTest
  @EnumSource(ReadBoardGmaSession.GmaTerminal.class)
  void everyGmaTerminalVariantFollowsItsFixedTerminalContract(
      ReadBoardGmaSession.GmaTerminal terminal) {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object intent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), intent, nonEmptySnapshot());
    ReadBoardGmaSession.GmaAuthorization authorization =
        ((ReadBoardGmaSession.GmaInFlight) session.state()).authorization();

    session.consumeGmaTerminal(terminalCapability, terminal);

    // The logical placement authorization expires when the GMA terminal line is consumed.
    assertTrue(authorization.invalidated());

    if (terminal == ReadBoardGmaSession.GmaTerminal.PLAYED) {
      // PLAYED is an authorized accepted move: the session succeeds directly — no exact or
      // runtime restore — and the terminal publishes, continues, and releases immediately.
      assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
      assertTrue(ports.exactStarts.isEmpty(), "PLAYED must not start exact restore");
      assertTrue(ports.runtimeStarts.isEmpty(), "PLAYED must not start runtime restore");
      assertEquals(
          List.of("publishTerminal", "continueNormal", "requestReservationRelease"),
          ports.calls);
      assertEquals(1, ports.publications.size());
      assertEquals(1, ports.releases.size());
      assertEquals(1, ports.continuations);
      return;
    }

    // Every other variant (PASS, RESIGN, REQUEST_ERROR, REQUEST_TIMEOUT) enters the exact
    // restore contract with the authoritative intent.
    ReadBoardGmaSession.RestoringExact exact =
        assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertSame(intent, exact.capturedExactOperation().restoreIntent());
    assertEquals(1, ports.exactStarts.size());
    assertEquals(1, ports.exactStarts.get(0).attempt());

    // The runtime phase runs only for a retired session; retire while RestoringExact.
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, session.state());
    assertEquals(1, ports.runtimeStarts.size());
    assertEquals(2, ports.runtimeStarts.get(0).attempt());

    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(
        List.of(
            "startExact", "startRuntime", "publishTerminal", "requestReservationRelease"),
        ports.calls);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
  }

  @Test
  void deferExactRestoreIsLatestWinsAndHoldsTerminalWhileRestoringExact() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object intent = new Object();
    Object latestIntent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), intent, nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    ReadBoardGmaSession.ExactParticipantCapability firstExact = ports.exactStarts.get(0);

    // Deferred requests are accepted while RestoringExact and are latest-wins.
    assertTrue(session.deferExactRestore(terminalCapability, new Object()));
    assertTrue(session.deferExactRestore(terminalCapability, latestIntent));

    // Exact success with a deferred request pending neither publishes nor releases: it starts a
    // new exact attempt with the latest deferred intent.
    session.completeExact(firstExact, new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertEquals(2, ports.exactStarts.size());
    assertEquals(2, ports.exactStarts.get(1).attempt());
    assertSame(latestIntent, ports.exactIntents.get(1));
    assertTrue(ports.publications.isEmpty());
    assertTrue(ports.releases.isEmpty());

    // The deferred exact runs to completion and only then reaches the terminal.
    session.completeExact(
        ports.exactStarts.get(1), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(
        List.of(
            "startExact", "startExact", "publishTerminal", "continueNormal",
            "requestReservationRelease"),
        ports.calls);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(1, ports.continuations);
  }

  @Test
  void deferExactRestoreHoldsTerminalWhileRestoringRuntimeAndRunsNewExactFirst() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object intent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), intent, nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.RESIGN);
    session.retire(session.helperCapability());
    ReadBoardGmaSession.ExactParticipantCapability firstExact = ports.exactStarts.get(0);
    session.completeExact(firstExact, new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, session.state());
    assertEquals(1, ports.runtimeStarts.size());

    // Deferred requests are also accepted while RestoringRuntime and are latest-wins.
    assertTrue(session.deferExactRestore(terminalCapability, new Object()));
    assertTrue(session.deferExactRestore(terminalCapability, intent));

    // Runtime success with a deferred request pending neither publishes nor releases: the latest
    // deferred intent starts a new exact attempt first.
    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertEquals(2, ports.exactStarts.size());
    assertEquals(3, ports.exactStarts.get(1).attempt());
    assertSame(intent, ports.exactIntents.get(1));
    assertTrue(ports.publications.isEmpty());
    assertTrue(ports.releases.isEmpty());

    // The deferred exact runs to completion and only then publishes and releases; a retired
    // session never continues.
    session.completeExact(
        ports.exactStarts.get(1), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(
        List.of(
            "startExact", "startRuntime", "startExact", "publishTerminal",
            "requestReservationRelease"),
        ports.calls);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(0, ports.continuations);
  }

  @Test
  void deferExactRestoreAbsorbsStaleAndPostTerminalCapabilityCalls() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object staleIntent = new Object();
    Object latestIntent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    // While the GMA request is in flight the terminal line has not been consumed: no deferral.
    assertFalse(session.deferExactRestore(terminalCapability, new Object()));

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.PASS);
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());

    // A terminal capability from another session or from a twin of this incarnation is absorbed.
    RecordingPorts foreignPorts = new RecordingPorts();
    ReadBoardGmaSession foreign = createSession(new Object(), foreignPorts);
    ReadBoardGmaSession.GmaTerminalCapability foreignTerminal =
        foreign.admitGma(
            foreign.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    assertFalse(session.deferExactRestore(foreignTerminal, new Object()));

    ReadBoardGmaSession twin = createSession(INCARNATION, new RecordingPorts());
    ReadBoardGmaSession.GmaTerminalCapability twinTerminal =
        twin.admitGma(
            twin.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    assertFalse(session.deferExactRestore(twinTerminal, new Object()));

    // The session's own terminal capability is accepted and latest-wins.
    assertTrue(session.deferExactRestore(terminalCapability, staleIntent));
    assertTrue(session.deferExactRestore(terminalCapability, latestIntent));
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(2, ports.exactStarts.size());
    assertSame(latestIntent, ports.exactIntents.get(1));
    assertNotSame(staleIntent, ports.exactIntents.get(1));

    session.completeExact(
        ports.exactStarts.get(1), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());

    // After the terminal, deferrals through the same capability are absorbed.
    assertFalse(session.deferExactRestore(terminalCapability, new Object()));
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(2, ports.exactStarts.size());
  }

  @Test
  void duplicateGmaTerminalIsAbsorbedAndStartsExactOnce() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    assertEquals(1, ports.exactStarts.size());
    assertEquals(List.of("startExact"), ports.calls);
  }

  @Test
  void gmaTerminalFromAnotherSessionOrIncarnationIsAbsorbedWithZeroEffects() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    assertEquals(1, ports.exactStarts.size());

    // Another session with the same engine incarnation: identity guard fires.
    ReadBoardGmaSession twin = createSession(INCARNATION, new RecordingPorts());
    ReadBoardGmaSession.GmaTerminalCapability twinTerminalCapability =
        twin.admitGma(
            twin.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    twin.consumeGmaTerminal(twinTerminalCapability, ReadBoardGmaSession.GmaTerminal.PASS);
    session.consumeGmaTerminal(
        twinTerminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    // Another session with a different engine incarnation: incarnation guard fires too.
    ReadBoardGmaSession foreign = createSession(new Object(), new RecordingPorts());
    ReadBoardGmaSession.GmaTerminalCapability foreignTerminalCapability =
        foreign.admitGma(
            foreign.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    foreign.consumeGmaTerminal(foreignTerminalCapability, ReadBoardGmaSession.GmaTerminal.RESIGN);
    session.consumeGmaTerminal(
        foreignTerminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertEquals(1, ports.exactStarts.size());
    assertEquals(List.of("startExact"), ports.calls);
  }

  @Test
  void retirementDuringGmaInFlightKeepsConvergingAndBlocksHelperEvents() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.HelperCapability helper = session.helperCapability();
    Object intent = new Object();
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(helper, intent, ReadBoardGmaSession.RuntimeSnapshot.empty());
    ReadBoardGmaSession.GmaAuthorization authorization =
        ((ReadBoardGmaSession.GmaInFlight) session.state()).authorization();

    session.retire(helper);
    session.retire(helper);

    assertTrue(session.retired());
    assertTrue(authorization.invalidated());
    assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state());
    assertTrue(ports.calls.isEmpty());
    // The helper capability is revoked: no new admission, no new intent capture.
    assertNull(session.admitGma(helper, new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty()));
    session.updateRestoreIntent(helper, new Object());
    assertSame(
        intent,
        assertInstanceOf(ReadBoardGmaSession.GmaInFlight.class, session.state())
            .authoritativeRestoreIntent());
    // The session-owned terminal capability is not revoked: exact restore still starts.
    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertEquals(1, ports.exactStarts.size());
  }

  @Test
  void exactSuccessStartsRuntimeExactlyOnceWithCapturedSnapshot() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    Object intent = new Object();
    Object latestIntent = new Object();
    List<Object> capturedParams = new ArrayList<>();
    capturedParams.add(new Object());
    ReadBoardGmaSession.RuntimeSnapshot snapshot =
        ReadBoardGmaSession.RuntimeSnapshot.of(capturedParams);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), intent, snapshot);
    session.updateRestoreIntent(session.helperCapability(), latestIntent);

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    // The runtime phase runs only for a retired session; retire while RestoringExact.
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    ReadBoardGmaSession.RestoringRuntime runtime =
        assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, session.state());
    assertSame(snapshot, runtime.capturedRuntimeSnapshot());
    assertSame(latestIntent, ports.exactIntents.get(0));
    assertEquals(List.of("startExact", "startRuntime"), ports.calls);
    assertEquals(1, ports.runtimeStarts.size());
    assertEquals(2, ports.runtimeStarts.get(0).attempt());
    assertSame(snapshot, ports.runtimeSnapshots.get(0));
    assertSame(capturedParams.get(0), snapshot.parameters().get(0));
    assertTrue(ports.publications.isEmpty());

    // The captured snapshot is restored before the retired session reaches its terminal.
    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(1, ports.publications.size());
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.releases.size());
  }

  @Test
  void retiredExactSuccessWithEmptyRuntimeSnapshotTerminatesSucceededImmediately() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    // Even a retired session skips the runtime phase when there is no captured runtime work.
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(
        List.of("startExact", "publishTerminal", "requestReservationRelease"), ports.calls);
    assertEquals(0, ports.runtimeStarts.size());
    assertEquals(1, ports.publications.size());
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.releases.size());
  }

  @Test
  void retiredRuntimeSuccessPublishesAndReleasesExactlyOnceInOrder() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(
        List.of("startExact", "startRuntime", "publishTerminal", "requestReservationRelease"),
        ports.calls);
    assertEquals(1, ports.publications.size());
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.releases.size());
  }

  @Test
  void exactFailureLocksFirstFailureAndNeverStartsRuntime() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());
    ReadBoardGmaSession.ParticipantFailure failure =
        new ReadBoardGmaSession.ParticipantFailure(
            ReadBoardGmaSession.FailureCategory.GTP_ERROR, INCARNATION, "loadsgf rejected");

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Failed(failure));
    // Late success through the same capability is absorbed; the first failure stays locked.
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertSame(failure, terminal.firstFailure());
    assertEquals(
        List.of("startExact", "handleFailure", "publishTerminal", "requestReservationRelease"),
        ports.calls);
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.releases.size());
    assertSame(failure, ports.failures.get(0));
  }

  @Test
  void runtimeFailureLocksFirstFailureAcrossAbsorbedLateEvents() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());
    ReadBoardGmaSession.ParticipantFailure failure =
        new ReadBoardGmaSession.ParticipantFailure(
            ReadBoardGmaSession.FailureCategory.TIMEOUT, INCARNATION, "restore response timeout");

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Failed(failure));
    // Late duplicate success and the already-consumed exact capability cannot rewrite the lock.
    session.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertSame(failure, terminal.firstFailure());
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.failures.size());
    assertEquals(1, ports.releases.size());
    assertEquals(0, ports.continuations);
  }

  @Test
  void preparingRetirementCancelsNoEffectWithoutQuarantineOrContinuation() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);

    session.retire(session.helperCapability());
    session.retire(session.helperCapability());

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.CANCELLED_NO_EFFECT, terminal.outcome());
    assertNull(terminal.firstFailure());
    assertTrue(session.retired());
    assertEquals(List.of("publishTerminal", "requestReservationRelease"), ports.calls);
    assertEquals(0, ports.failures.size());
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.releases.size());
  }

  @Test
  void retirementDuringExactOrRuntimeKeepsConvergingWithoutContinuation() {
    RecordingPorts exactPhasePorts = new RecordingPorts();
    ReadBoardGmaSession exactPhase = createSession(exactPhasePorts);
    ReadBoardGmaSession.GmaTerminalCapability exactPhaseTerminal =
        exactPhase.admitGma(exactPhase.helperCapability(), new Object(), nonEmptySnapshot());
    exactPhase.consumeGmaTerminal(
        exactPhaseTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    exactPhase.retire(exactPhase.helperCapability());
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, exactPhase.state());
    assertEquals(List.of("startExact"), exactPhasePorts.calls);

    exactPhase.completeExact(
        exactPhasePorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    exactPhase.completeRuntime(
        exactPhasePorts.runtimeStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(exactPhase).outcome());
    assertEquals(
        List.of("startExact", "startRuntime", "publishTerminal", "requestReservationRelease"),
        exactPhasePorts.calls);
    assertEquals(0, exactPhasePorts.continuations);
    assertEquals(1, exactPhasePorts.releases.size());

    RecordingPorts runtimePhasePorts = new RecordingPorts();
    ReadBoardGmaSession runtimePhase = createSession(runtimePhasePorts);
    ReadBoardGmaSession.GmaTerminalCapability runtimePhaseTerminal =
        runtimePhase.admitGma(runtimePhase.helperCapability(), new Object(), nonEmptySnapshot());
    runtimePhase.consumeGmaTerminal(
        runtimePhaseTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    runtimePhase.retire(runtimePhase.helperCapability());
    runtimePhase.completeExact(
        runtimePhasePorts.exactStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, runtimePhase.state());
    // A retire that lands while RestoringRuntime is absorbed; the phase keeps converging.
    runtimePhase.retire(runtimePhase.helperCapability());

    runtimePhase.completeRuntime(
        runtimePhasePorts.runtimeStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(1, runtimePhasePorts.publications.size());
    assertEquals(1, runtimePhasePorts.releases.size());
    assertEquals(0, runtimePhasePorts.continuations);
  }

  @Test
  void engineTerminationWhileGmaIsInFlightFailsThroughSessionExactlyOnce() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    assertTrue(session.failEngineProcess(terminalCapability, "engine exited"));
    assertFalse(session.failEngineProcess(terminalCapability, "duplicate engine exit"));

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertEquals(
        ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
        terminal.firstFailure().category());
    assertSame(INCARNATION, terminal.firstFailure().engineIncarnation());
    assertEquals(
        List.of("handleFailure", "publishTerminal", "requestReservationRelease"), ports.calls);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(0, ports.continuations);
  }

  @Test
  void engineTerminationDuringExactAndRuntimeAbsorbsLateParticipantCallbacks() {
    RecordingPorts exactPorts = new RecordingPorts();
    ReadBoardGmaSession exactSession = createSession(exactPorts);
    ReadBoardGmaSession.GmaTerminalCapability exactTerminal =
        exactSession.admitGma(exactSession.helperCapability(), new Object(), nonEmptySnapshot());
    exactSession.consumeGmaTerminal(exactTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    ReadBoardGmaSession.ExactParticipantCapability exactCapability =
        exactPorts.exactStarts.get(0);

    assertTrue(exactSession.failEngineProcess(exactTerminal, "engine exited during exact"));
    exactSession.completeExact(
        exactCapability, new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(
        ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
        terminalOf(exactSession).firstFailure().category());
    assertEquals(
        List.of(
            "startExact", "handleFailure", "publishTerminal", "requestReservationRelease"),
        exactPorts.calls);

    RecordingPorts runtimePorts = new RecordingPorts();
    ReadBoardGmaSession runtimeSession = createSession(runtimePorts);
    ReadBoardGmaSession.GmaTerminalCapability runtimeTerminal =
        runtimeSession.admitGma(
            runtimeSession.helperCapability(), new Object(), nonEmptySnapshot());
    runtimeSession.consumeGmaTerminal(
        runtimeTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    runtimeSession.retire(runtimeSession.helperCapability());
    runtimeSession.completeExact(
        runtimePorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    ReadBoardGmaSession.RuntimeParticipantCapability runtimeCapability =
        runtimePorts.runtimeStarts.get(0);

    assertTrue(runtimeSession.failEngineProcess(runtimeTerminal, "engine exited during runtime"));
    runtimeSession.completeRuntime(
        runtimeCapability, new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(
        ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
        terminalOf(runtimeSession).firstFailure().category());
    assertEquals(
        List.of(
            "startExact",
            "startRuntime",
            "handleFailure",
            "publishTerminal",
            "requestReservationRelease"),
        runtimePorts.calls);
  }

  @Test
  void retiredSessionFailureStillHandlesFailureAndReleasesExactlyOnce() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());
    ReadBoardGmaSession.ParticipantFailure failure =
        new ReadBoardGmaSession.ParticipantFailure(
            ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED, INCARNATION, "engine exited");

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Failed(failure));

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertSame(failure, terminal.firstFailure());
    assertEquals(
        List.of("startExact", "handleFailure", "publishTerminal", "requestReservationRelease"),
        ports.calls);
    assertEquals(0, ports.continuations);
  }

  @Test
  void exactStartPortRejectionConvertsToTypedFailureAndFailCloses() {
    RejectingExactStartPorts ports = new RejectingExactStartPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertEquals(
        ReadBoardGmaSession.FailureCategory.START_REJECTED, terminal.firstFailure().category());
    assertSame(INCARNATION, terminal.firstFailure().engineIncarnation());
    assertEquals(
        List.of("startExact", "handleFailure", "publishTerminal", "requestReservationRelease"),
        ports.calls);
    assertEquals(0, ports.runtimeStarts.size());
    assertEquals(0, ports.continuations);
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
  }

  @Test
  void runtimeStartPortRejectionConvertsToTypedFailureAndFailCloses() {
    RejectingRuntimeStartPorts ports = new RejectingRuntimeStartPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    ReadBoardGmaSession.Terminal terminal = terminalOf(session);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertEquals(
        ReadBoardGmaSession.FailureCategory.START_REJECTED, terminal.firstFailure().category());
    assertEquals(
        List.of(
            "startExact",
            "startRuntime",
            "handleFailure",
            "publishTerminal",
            "requestReservationRelease"),
        ports.calls);
    assertEquals(1, ports.runtimeStarts.size());
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
  }

  @Test
  void terminalAbsorbsEveryStaleEvent() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    int callsAfterSuccess = ports.calls.size();

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    session.retire(session.helperCapability());
    session.updateRestoreIntent(session.helperCapability(), new Object());
    session.invalidateAuthorization(session.helperCapability());
    assertNull(
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty()));

    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(callsAfterSuccess, ports.calls.size());
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(1, ports.continuations);
  }

  @Test
  void participantCapabilitiesArePhaseBoundAndAttemptBound() {
    RecordingPorts ports = new RecordingPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());
    assertEquals(0, terminalCapability.attempt());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    assertEquals(1, ports.exactStarts.get(0).attempt());
    session.retire(session.helperCapability());
    session.completeExact(
        ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(2, ports.runtimeStarts.get(0).attempt());
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, session.state());

    // Cross-phase delivery is absorbed: the exact capability cannot act while RestoringRuntime.
    session.completeExact(
        ports.exactStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Failed(
            new ReadBoardGmaSession.ParticipantFailure(
                ReadBoardGmaSession.FailureCategory.GTP_ERROR, INCARNATION, null)));
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, session.state());
    assertEquals(List.of("startExact", "startRuntime"), ports.calls);

    // A runtime capability issued by another session cannot advance this session's exact phase.
    RecordingPorts otherPorts = new RecordingPorts();
    ReadBoardGmaSession other = createSession(otherPorts);
    ReadBoardGmaSession.GmaTerminalCapability otherTerminal =
        other.admitGma(other.helperCapability(), new Object(), nonEmptySnapshot());
    other.consumeGmaTerminal(otherTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    other.completeRuntime(
        ports.runtimeStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, other.state());
    assertEquals(List.of("startExact"), otherPorts.calls);
  }

  @Test
  void staleSessionEventsDoNotAffectNewerSession() {
    RecordingPorts olderPorts = new RecordingPorts();
    ReadBoardGmaSession older = createSession(olderPorts);
    ReadBoardGmaSession.GmaTerminalCapability olderTerminal =
        older.admitGma(older.helperCapability(), new Object(), nonEmptySnapshot());
    older.consumeGmaTerminal(olderTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    RecordingPorts newerPorts = new RecordingPorts();
    ReadBoardGmaSession newer = createSession(newerPorts);
    ReadBoardGmaSession.GmaTerminalCapability newerTerminal =
        newer.admitGma(
            newer.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    newer.consumeGmaTerminal(newerTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    newer.completeExact(
        newerPorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    assertEquals(1, newerPorts.publications.size());
    assertEquals(0, olderPorts.publications.size());

    // The old session's in-flight participant still converges to its own terminal: its active
    // exact success reaches the terminal directly without the runtime phase.
    older.completeExact(
        olderPorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(older).outcome());
    assertEquals(1, olderPorts.publications.size());
    assertEquals(1, olderPorts.releases.size());
    assertEquals(1, newerPorts.releases.size());
    assertNotSame(olderPorts.releases.get(0), newerPorts.releases.get(0));
    assertEquals(1, olderPorts.continuations);
    assertEquals(1, newerPorts.continuations);
  }

  @Test
  void reservationReleaseCapabilityIsBoundAndDeliveredOncePerSession() {
    RecordingPorts portsA = new RecordingPorts();
    ReadBoardGmaSession sessionA = createSession(portsA);
    ReadBoardGmaSession.GmaTerminalCapability terminalA =
        sessionA.admitGma(
            sessionA.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    sessionA.consumeGmaTerminal(terminalA, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    sessionA.completeExact(
        portsA.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    RecordingPorts portsB = new RecordingPorts();
    ReadBoardGmaSession sessionB = createSession(portsB);
    ReadBoardGmaSession.GmaTerminalCapability terminalB =
        sessionB.admitGma(
            sessionB.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    sessionB.consumeGmaTerminal(terminalB, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    sessionB.completeExact(
        portsB.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());

    ReadBoardGmaSession.ReservationReleaseCapability releaseA = portsA.releases.get(0);
    ReadBoardGmaSession.ReservationReleaseCapability releaseB = portsB.releases.get(0);
    assertNotSame(releaseA, releaseB);
    assertEquals(1, portsA.releases.size());
    assertEquals(1, portsB.releases.size());
    assertSame(INCARNATION, releaseA.engineIncarnation());
    assertSame(INCARNATION, releaseB.engineIncarnation());
  }

  @Test
  void foreignIncarnationFailureIsAbsorbedAndCannotLockTheSession() {
    RecordingPorts exactPorts = new RecordingPorts();
    ReadBoardGmaSession session = createSession(exactPorts);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());
    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    // A failure payload bound to a foreign engine incarnation cannot lock this session.
    session.completeExact(
        exactPorts.exactStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Failed(
            new ReadBoardGmaSession.ParticipantFailure(
                ReadBoardGmaSession.FailureCategory.GTP_ERROR, new Object(), "foreign")));
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertEquals(List.of("startExact"), exactPorts.calls);

    // The same session and incarnation still converge normally afterwards: an active session's
    // exact success reaches the terminal directly without starting the runtime phase.
    session.completeExact(
        exactPorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertTrue(exactPorts.runtimeStarts.isEmpty());
    assertEquals(1, exactPorts.publications.size());
    assertEquals(1, exactPorts.releases.size());

    RecordingPorts runtimePorts = new RecordingPorts();
    ReadBoardGmaSession runtimeSession = createSession(runtimePorts);
    ReadBoardGmaSession.GmaTerminalCapability runtimeTerminal =
        runtimeSession.admitGma(
            runtimeSession.helperCapability(), new Object(), nonEmptySnapshot());
    runtimeSession.consumeGmaTerminal(
        runtimeTerminal, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    runtimeSession.retire(runtimeSession.helperCapability());
    runtimeSession.completeExact(
        runtimePorts.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded());
    runtimeSession.completeRuntime(
        runtimePorts.runtimeStarts.get(0),
        new ReadBoardGmaSession.ParticipantResult.Failed(
            new ReadBoardGmaSession.ParticipantFailure(
                ReadBoardGmaSession.FailureCategory.TIMEOUT, new Object(), "foreign")));
    assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, runtimeSession.state());
    assertEquals(List.of("startExact", "startRuntime"), runtimePorts.calls);
  }

  @Test
  void effectPortFailureNeverSuppressesLaterEffectsAndIsRethrown() {
    FailingPublishPorts ports = new FailingPublishPorts();
    ReadBoardGmaSession session = createSession(ports);
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(session.helperCapability(), new Object(), nonEmptySnapshot());

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    // The active session's exact success publishes the terminal; the publish failure must not
    // suppress the continuation and release effects and is rethrown after the batch applied.
    assertThrows(
        IllegalStateException.class,
        () ->
            session.completeExact(
                ports.exactStarts.get(0), new ReadBoardGmaSession.ParticipantResult.Succeeded()));

    // The publish failure did not suppress the continuation and release effects.
    assertEquals(
        List.of(
            "startExact", "publishTerminal", "continueNormal", "requestReservationRelease"),
        ports.calls);
    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(1, ports.continuations);
    assertEquals(1, ports.releases.size());
  }

  private static ReadBoardGmaSession createSession(ReadBoardGmaSession.Ports ports) {
    return createSession(INCARNATION, ports);
  }

  private static ReadBoardGmaSession createSession(
      Object engineIncarnation, ReadBoardGmaSession.Ports ports) {
    return ReadBoardGmaSession.create(engineIncarnation, new Object(), ports);
  }

  private static ReadBoardGmaSession.RuntimeSnapshot nonEmptySnapshot() {
    List<Object> capturedParams = new ArrayList<>();
    capturedParams.add(new Object());
    return ReadBoardGmaSession.RuntimeSnapshot.of(capturedParams);
  }

  private static ReadBoardGmaSession.Terminal terminalOf(ReadBoardGmaSession session) {
    return assertInstanceOf(ReadBoardGmaSession.Terminal.class, session.state());
  }

  /** Records every port call in arrival order with its payloads. */
  private static class RecordingPorts implements ReadBoardGmaSession.Ports {
    final List<String> calls = new ArrayList<>();
    final List<ReadBoardGmaSession.ExactParticipantCapability> exactStarts = new ArrayList<>();
    final List<Object> exactIntents = new ArrayList<>();
    final List<ReadBoardGmaSession.RuntimeParticipantCapability> runtimeStarts = new ArrayList<>();
    final List<ReadBoardGmaSession.RuntimeSnapshot> runtimeSnapshots = new ArrayList<>();
    final List<ReadBoardGmaSession.Terminal> publications = new ArrayList<>();
    final List<ReadBoardGmaSession.ParticipantFailure> failures = new ArrayList<>();
    final List<ReadBoardGmaSession.ReservationReleaseCapability> releases = new ArrayList<>();
    int continuations;

    @Override
    public void startExact(
        ReadBoardGmaSession.ExactParticipantCapability capability, Object restoreIntent) {
      calls.add("startExact");
      exactStarts.add(capability);
      exactIntents.add(restoreIntent);
    }

    @Override
    public void startRuntime(
        ReadBoardGmaSession.RuntimeParticipantCapability capability,
        ReadBoardGmaSession.RuntimeSnapshot runtimeSnapshot) {
      calls.add("startRuntime");
      runtimeStarts.add(capability);
      runtimeSnapshots.add(runtimeSnapshot);
    }

    @Override
    public void publishTerminal(ReadBoardGmaSession.Terminal terminal) {
      calls.add("publishTerminal");
      publications.add(terminal);
    }

    @Override
    public void handleFailure(ReadBoardGmaSession.ParticipantFailure firstFailure) {
      calls.add("handleFailure");
      failures.add(firstFailure);
    }

    @Override
    public void continueNormal() {
      calls.add("continueNormal");
      continuations++;
    }

    @Override
    public void requestReservationRelease(
        ReadBoardGmaSession.ReservationReleaseCapability capability) {
      calls.add("requestReservationRelease");
      releases.add(capability);
    }
  }

  /** startExact port that synchronously rejects; the module must convert it to a typed failure. */
  private static final class RejectingExactStartPorts extends RecordingPorts {
    @Override
    public void startExact(
        ReadBoardGmaSession.ExactParticipantCapability capability, Object restoreIntent) {
      super.startExact(capability, restoreIntent);
      throw new IllegalStateException("exact participant rejected");
    }
  }

  /**
   * startRuntime port that synchronously rejects; the module must convert it to a typed failure.
   */
  private static final class RejectingRuntimeStartPorts extends RecordingPorts {
    @Override
    public void startRuntime(
        ReadBoardGmaSession.RuntimeParticipantCapability capability,
        ReadBoardGmaSession.RuntimeSnapshot runtimeSnapshot) {
      super.startRuntime(capability, runtimeSnapshot);
      throw new IllegalStateException("runtime participant rejected");
    }
  }

  /**
   * publishTerminal port that fails; later effects must still apply and the failure must surface.
   */
  private static final class FailingPublishPorts extends RecordingPorts {
    @Override
    public void publishTerminal(ReadBoardGmaSession.Terminal terminal) {
      super.publishTerminal(terminal);
      throw new IllegalStateException("publish failed");
    }
  }

  @Test
  void exactParticipantReportsSuccessOnlyAfterRestoreOperationCompletes() throws Exception {
    ParticipantPorts ports = new ParticipantPorts();
    ReadBoardGmaSession session = createSession(ports);
    ports.session = session;
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    CountDownLatch operationStarted = new CountDownLatch(1);
    CountDownLatch releaseOperation = new CountDownLatch(1);
    ports.restoreOperation =
        () -> {
          operationStarted.countDown();
          awaitLatch(releaseOperation);
        };

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);

    // The participant is blocked inside the restore operation: the completion boundary (loadsgf
    // consumed and captured tail accepted into the ordinary queue) has not been reached, so no
    // terminal may be published and the session must stay RestoringExact.
    assertTrue(operationStarted.await(1, TimeUnit.SECONDS));
    assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, session.state());
    assertTrue(ports.publications.isEmpty());
    assertTrue(ports.releases.isEmpty());

    releaseOperation.countDown();
    awaitPublication(ports);

    assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminalOf(session).outcome());
    assertEquals(1, ports.publications.size());
    assertEquals(1, ports.releases.size());
    assertEquals(1, ports.continuations);
  }

  @ParameterizedTest
  @MethodSource("restoreFailureSeam")
  void exactParticipantMapsRestoreFailuresToTypedCategories(
      RuntimeException failure, ReadBoardGmaSession.FailureCategory expectedCategory)
      throws Exception {
    ParticipantPorts ports = new ParticipantPorts();
    ReadBoardGmaSession session = createSession(ports);
    ports.session = session;
    ReadBoardGmaSession.GmaTerminalCapability terminalCapability =
        session.admitGma(
            session.helperCapability(), new Object(), ReadBoardGmaSession.RuntimeSnapshot.empty());
    ports.restoreOperation =
        () -> {
          throw failure;
        };

    session.consumeGmaTerminal(terminalCapability, ReadBoardGmaSession.GmaTerminal.REQUEST_ERROR);
    awaitPublication(ports);

    ReadBoardGmaSession.Terminal terminal = ports.publications.get(0);
    assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
    assertEquals(expectedCategory, terminal.firstFailure().category());
    assertEquals(INCARNATION, terminal.firstFailure().engineIncarnation());
    assertTrue(
        ports.runtimeStarts.isEmpty(),
        "an exact failure must never start the runtime participant");
    assertEquals(0, ports.continuations, "an exact failure must never continue normal GMA");
  }

  static Stream<Object[]> restoreFailureSeam() {
    return Stream.of(
        new Object[] {
          new ExactSnapshotEngineRestore.Failure(
              ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR,
              "GTP loadsgf failed for '/tmp/lizzie-snapshot-1.sgf' with response: ? invalid setup"),
          ReadBoardGmaSession.FailureCategory.GTP_ERROR
        },
        new Object[] {
          new ExactSnapshotEngineRestore.Failure(
              ExactSnapshotEngineRestore.FailureCategory.TIMEOUT,
              "Timed out while waiting for loadsgf response after 8000 ms"),
          ReadBoardGmaSession.FailureCategory.TIMEOUT
        },
        new Object[] {
          new ExactSnapshotEngineRestore.Failure(
              ExactSnapshotEngineRestore.FailureCategory.ADMISSION_STALE,
              "Exact snapshot restore admission is no longer valid"),
          ReadBoardGmaSession.FailureCategory.ADMISSION_STALE
        },
        new Object[] {
          new ExactSnapshotEngineRestore.Failure(
              ExactSnapshotEngineRestore.FailureCategory.SEND_FAILED,
              "Exact snapshot restore loadsgf command was rejected: loadsgf /tmp/lizzie-snapshot-1.sgf"),
          ReadBoardGmaSession.FailureCategory.SEND_FAILED
        },
        new Object[] {
          new ExactSnapshotEngineRestore.Failure(
              ExactSnapshotEngineRestore.FailureCategory.TAIL_REJECTED,
              "Exact snapshot restore tail command was rejected: play W D4"),
          ReadBoardGmaSession.FailureCategory.TAIL_REJECTED
        },
        // Untyped restore failures still map to the stable SEND_FAILED fallback.
        new Object[] {
          new IllegalStateException("unexpected restore failure"),
          ReadBoardGmaSession.FailureCategory.SEND_FAILED
        });
  }

  /**
   * Ports whose {@code startExact} runs a {@link ReadBoard.ReadBoardGmaExactParticipant} with a
   * controllable restore operation, and which latch terminal publication for deterministic
   * assertions.
   */
  private static final class ParticipantPorts extends RecordingPorts {
    ReadBoardGmaSession session;
    ReadBoard.ReadBoardGmaExactParticipant.RestoreOperation restoreOperation = () -> {};
    private final CountDownLatch published = new CountDownLatch(1);

    @Override
    public void startExact(
        ReadBoardGmaSession.ExactParticipantCapability capability, Object restoreIntent) {
      super.startExact(capability, restoreIntent);
      ReadBoard.ReadBoardGmaExactParticipant.start(session, capability, restoreOperation);
    }

    @Override
    public void publishTerminal(ReadBoardGmaSession.Terminal terminal) {
      super.publishTerminal(terminal);
      published.countDown();
    }
  }

  private static void awaitPublication(ParticipantPorts ports) throws InterruptedException {
    assertTrue(ports.published.await(1, TimeUnit.SECONDS));
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
