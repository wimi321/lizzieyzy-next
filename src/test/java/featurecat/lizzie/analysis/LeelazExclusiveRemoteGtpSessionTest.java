package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    Leelaz engine = remoteEngine();
    ByteArrayOutputStream bytes = installOutput(engine);
    List<String> received = new ArrayList<>();
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();

    assertTrue(
        engine.beginExclusiveRemoteGtpSession(
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
    assertTrue(engine.sendExclusiveRemoteGtpCommand("kata-raw-nn 0"));
    assertEquals(
        "800000000 stop\nkata-raw-nn 0\n", bytes.toString(StandardCharsets.UTF_8));

    assertTrue(dispatch(engine, "= symmetry 0"));
    assertTrue(dispatch(engine, "whiteWin 0.61"));
    assertEquals(List.of("= symmetry 0", "whiteWin 0.61"), received);

    engine.endExclusiveRemoteGtpSession();
    assertFalse(engine.sendExclusiveRemoteGtpCommand("kata-raw-nn 0"));
    assertEquals(0, closed.get());
  }

  @Test
  void exclusiveRemoteSessionAbortsCleanlyOnStopError() throws Exception {
    Leelaz engine = remoteEngine();
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();

    assertTrue(
        engine.beginExclusiveRemoteGtpSession(line -> {}, () -> {}, closed::incrementAndGet));
    assertFalse(dispatch(engine, "?800000000 cannot stop"));

    assertEquals(1, closed.get());
    assertFalse(engine.sendExclusiveRemoteGtpCommand("kata-raw-nn 0"));
  }

  private static Leelaz remoteEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.useRemoteCompute = true;
    engine.isLoaded = true;
    engine.started = true;
    return engine;
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedOutputStream(bytes));
    return bytes;
  }

  private static boolean dispatch(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveRemoteGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }
}
