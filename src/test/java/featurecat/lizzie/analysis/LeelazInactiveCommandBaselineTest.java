package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeelazInactiveCommandBaselineTest {
  @Test
  void ordinaryEntriesShareQueueOrderAndAnalyzeCoalescingWithoutChangingBytes() throws Exception {
    try (TestHarness ignored = TestHarness.open(ExtraMode.Normal)) {
      Leelaz engine = new Leelaz("");
      ByteArrayOutputStream output = installOutput(engine);
      engine.isKatago = true;
      engine.requireResponseBeforeSend = true;

      engine.sendCommand("name");
      engine.sendCommand("kata-analyze interval 10");
      engine.sendCommandNoLeelaz2("version");
      engine.sendCommand("showboard");

      assertEquals("name\n", output.toString(StandardCharsets.UTF_8));

      processCommandResponse(engine, "=");
      assertEquals("name\nversion\n", output.toString(StandardCharsets.UTF_8));

      processCommandResponse(engine, "=");
      assertEquals("name\nversion\nshowboard\n", output.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void defaultEntryMirrorsButNoLeelaz2EntryStaysOnTheTargetEngine() throws Exception {
    try (TestHarness ignored = TestHarness.open(ExtraMode.Double_Engine)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      ByteArrayOutputStream primaryOutput = installOutput(primary);
      ByteArrayOutputStream secondaryOutput = installOutput(secondary);
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      primary.sendCommand("name");
      primary.sendCommandNoLeelaz2("version");

      assertEquals("name\nversion\n", primaryOutput.toString(StandardCharsets.UTF_8));
      assertEquals("name\n", secondaryOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void ordinaryUnnumberedResponsesRunHandlersOnceInQueueOrder() throws Exception {
    try (TestHarness ignored = TestHarness.open(ExtraMode.Normal)) {
      Leelaz engine = new Leelaz("");
      installOutput(engine);
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();

      sendCommandWithResponse(engine, "name", first::incrementAndGet);
      sendCommandNoLeelaz2WithResponse(engine, "version", second::incrementAndGet);

      processCommandResponse(engine, "= KataGo");
      processCommandResponse(engine, "= 1.16");
      processCommandResponse(engine, "=");

      assertEquals(1, first.get());
      assertEquals(1, second.get());
    }
  }

  @Test
  void ordinarySendFailuresCompleteHandlersOnceAndDoNotStrandLaterCommands() throws Exception {
    try (TestHarness ignored = TestHarness.open(ExtraMode.Normal)) {
      assertOrdinarySendFailureCompletesHandlerAndQueue(false);
      assertOrdinarySendFailureCompletesHandlerAndQueue(true);
    }
  }

  @Test
  void existingExclusiveSessionBlocksBothOrdinaryEntriesUntilCloseThenResumesTheirQueue()
      throws Exception {
    try (TestHarness ignored = TestHarness.open(ExtraMode.Normal)) {
      Leelaz engine = reusableKatagoEngine();
      ByteArrayOutputStream output = installOutput(engine);

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
      engine.sendCommand("name");
      engine.sendCommandNoLeelaz2("version");

      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));

      dispatchExclusiveLine(engine, "=800000000");
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));

      engine.endExclusiveGtpSession();

      assertEquals("800000000 stop\nname\nversion\n", output.toString(StandardCharsets.UTF_8));
    }
  }

  private static Leelaz reusableKatagoEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    setField(engine, "endGetCommandList", true);
    return engine;
  }

  private static void assertOrdinarySendFailureCompletesHandlerAndQueue(boolean noLeelaz2EntryFails)
      throws Exception {
    Leelaz engine = new Leelaz("");
    FailBeforeFirstByteOutputStream output = new FailBeforeFirstByteOutputStream();
    installOutput(engine, Leelaz.createCommandOutputStream(output));
    AtomicInteger failedCommandHandler = new AtomicInteger();
    AtomicInteger successfulCommandHandler = new AtomicInteger();

    if (noLeelaz2EntryFails) {
      sendCommandNoLeelaz2WithResponse(engine, "name", failedCommandHandler::incrementAndGet);
      sendCommandWithResponse(engine, "version", successfulCommandHandler::incrementAndGet);
    } else {
      sendCommandWithResponse(engine, "name", failedCommandHandler::incrementAndGet);
      sendCommandNoLeelaz2WithResponse(
          engine, "version", successfulCommandHandler::incrementAndGet);
    }

    assertEquals(1, failedCommandHandler.get());
    assertEquals("version\n", output.writtenText());

    processCommandResponse(engine, "= 1.16");
    processCommandResponse(engine, "=");

    assertEquals(1, failedCommandHandler.get());
    assertEquals(1, successfulCommandHandler.get());
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    installOutput(engine, new BufferedOutputStream(output));
    return output;
  }

  private static void installOutput(Leelaz engine, BufferedOutputStream output) throws Exception {
    engine.installCommandOutputForTest(output);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(engine, line);
  }

  private static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse)
      throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("sendCommand", String.class, Runnable.class);
    method.setAccessible(true);
    method.invoke(engine, command, onResponse);
  }

  private static void sendCommandNoLeelaz2WithResponse(
      Leelaz engine, String command, Runnable onResponse) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod("sendCommandNoLeelaz2", String.class, Runnable.class);
    method.setAccessible(true);
    method.invoke(engine, command, onResponse);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class FailBeforeFirstByteOutputStream extends OutputStream {
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private boolean failed;

    @Override
    public void write(int value) throws IOException {
      if (!failed) {
        failed = true;
        // Fail before accepting any byte so the recoverable command stream remains usable by the
        // next queued command. A failure after accepting bytes is intentionally treated as a
        // polluted transport and invalidated by production code.
        throw new IOException("controlled ordinary send failure");
      }
      written.write(value);
    }

    private String writtenText() {
      return written.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;

    private TestHarness() {
      previousConfig = Lizzie.config;
      previousFrame = Lizzie.frame;
      previousLeelaz = Lizzie.leelaz;
      previousLeelaz2 = Lizzie.leelaz2;
    }

    private static TestHarness open(ExtraMode mode) throws Exception {
      TestHarness harness = new TestHarness();
      Lizzie.config = allocate(Config.class);
      Lizzie.config.extraMode = mode;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
