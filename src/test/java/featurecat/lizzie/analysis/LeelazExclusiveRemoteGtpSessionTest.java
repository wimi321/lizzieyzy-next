package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeelazExclusiveRemoteGtpSessionTest {

  @Test
  void exclusiveRemoteSessionWaitsForStopThenRoutesOnlyQuickCurveTraffic() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream bytes = installOutput(engine);
    List<String> received = new ArrayList<>();
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(
            received::add, ready::incrementAndGet, closed::incrementAndGet));
    assertEquals("800000000 stop\n", bytes.toString(StandardCharsets.UTF_8));
    assertTrue(dispatch(engine, "info move D4 visits 10"));
    assertEquals(0, ready.get());

    assertFalse(dispatch(engine, "="));
    processCommandResponse(engine, "=");
    assertEquals(0, ready.get(), "the analyze terminator is not the numbered stop response.");
    assertFalse(dispatch(engine, "=800000000"));
    processCommandResponse(engine, "=800000000");

    assertEquals(1, ready.get());
    assertTrue(dispatch(engine, ""), "the trailing stop boundary must be consumed once.");
    assertTrue(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals(
        "800000000 stop\nkata-raw-nn 0\n", bytes.toString(StandardCharsets.UTF_8));

    assertTrue(dispatch(engine, "= symmetry 0"));
    assertTrue(dispatch(engine, "whiteWin 0.61"));
    assertEquals(List.of("= symmetry 0", "whiteWin 0.61"), received);

    engine.endExclusiveGtpSession();
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals(0, closed.get());
  }

  @Test
  void exclusiveRemoteSessionAbortsCleanlyOnStopError() throws Exception {
    Leelaz engine = reusableKatagoEngine(true, false);
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, closed::incrementAndGet));
    assertFalse(dispatch(engine, "?800000000 cannot stop"));

    assertEquals(1, closed.get());
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
  }

  @Test
  void exclusiveSessionIgnoresUnrelatedStopResponsesAndRejectsSecondLease() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, ready::incrementAndGet, () -> {}));
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

    assertFalse(dispatch(engine, "=800000001"));
    processCommandResponse(engine, "=800000001");
    assertFalse(dispatch(engine, "?800000001 unrelated failure"));
    assertEquals(0, ready.get());
    assertTrue(engine.hasExclusiveGtpLease());

    assertFalse(dispatch(engine, "=800000000"));
    processCommandResponse(engine, "=800000000");
    assertEquals(1, ready.get());
    engine.endExclusiveGtpSession();
  }

  @Test
  void exclusiveSessionUsesSameTransportNeutralEntryPointForJavaSsh() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, true);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
  }

  @Test
  void exclusiveSessionRejectsNonKatagoAndMissingCapabilities() throws Exception {
    Leelaz nonKatago = reusableKatagoEngine(false, false);
    nonKatago.isKatago = false;
    installOutput(nonKatago);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.NOT_KATAGO,
        nonKatago.previewExclusiveGtpLeaseAvailability());

    Leelaz missingCapability = reusableKatagoEngine(false, false);
    missingCapability.commandLists.remove("kata-analyze");
    installOutput(missingCapability);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingCapability.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void exclusiveSessionWaitsForCapabilityDiscovery() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    setCapabilityDiscoveryComplete(engine, false);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
        engine.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void lifecycleTransitionAndLeaseAcquisitionShareOneAtomicGate() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);

    assertTrue(engine.beginExclusiveGtpLifecycleTransition());
    assertTrue(
        engine.beginExclusiveGtpLifecycleTransition(),
        "nested mode transitions on the same UI thread must remain atomic and usable.");
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    engine.endExclusiveGtpLifecycleTransition();
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    engine.endExclusiveGtpLifecycleTransition();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    assertFalse(engine.beginExclusiveGtpLifecycleTransition());
    engine.endExclusiveGtpSession();
  }

  @Test
  void foregroundLeaseCanOnlyBeReleasedByItsAcquisitionOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    Object owner = new Object();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));

      engine.endForegroundAnalysisLease(new Object());
      assertTrue(engine.hasExclusiveGtpLeaseOwnedBy(owner));

      Lizzie.leelaz = null;
      engine.endForegroundAnalysisLease(owner);
      assertFalse(engine.hasExclusiveGtpLease());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  private static Leelaz reusableKatagoEngine(boolean remoteCompute, boolean javaSsh)
      throws Exception {
    Leelaz engine = new Leelaz("");
    engine.useRemoteCompute = remoteCompute;
    engine.useJavaSSH = javaSsh;
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-set-rules",
            "clear_board",
            "play",
            "kata-analyze"));
    setCapabilityDiscoveryComplete(engine, true);
    return engine;
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean complete)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("endGetCommandList");
    field.setAccessible(true);
    field.set(engine, complete);
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedOutputStream(bytes));
    return bytes;
  }

  private static boolean dispatch(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

}
