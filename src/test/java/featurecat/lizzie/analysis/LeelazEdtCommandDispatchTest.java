package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeelazEdtCommandDispatchTest {
  @TempDir Path tempDir;

  private Config previousConfig;
  private Leelaz previousPrimary;
  private Leelaz previousSecondary;

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.leelaz2 = previousSecondary;
  }

  @Test
  void blockedEngineOutputNeverBlocksTheSwingEventThread() throws Exception {
    installGlobals();
    BlockingOutput output = new BlockingOutput();
    Leelaz engine = new Leelaz("");
    engine.installCommandOutputForTest(output);
    Lizzie.setPrimaryEngine(engine);

    CountDownLatch sendReturned = new CountDownLatch(1);
    CountDownLatch nextUiEventRan = new CountDownLatch(1);
    try {
      SwingUtilities.invokeLater(
          () -> {
            engine.sendCommandNoLeelaz2("play B D4");
            sendReturned.countDown();
          });

      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS), "engine write never started");
      SwingUtilities.invokeLater(nextUiEventRan::countDown);

      assertTrue(
          sendReturned.await(1, TimeUnit.SECONDS),
          "GTP output blocked the Swing event thread");
      assertTrue(
          nextUiEventRan.await(1, TimeUnit.SECONDS),
          "the next user input could not run while engine output was stalled");
      assertFalse(output.writeRanOnEventThread.get(), "physical GTP output ran on the Swing EDT");
    } finally {
      output.releaseWrite.countDown();
      output.writeCompleted.await(2, TimeUnit.SECONDS);
    }
  }

  private void installGlobals() throws Exception {
    previousConfig = Lizzie.config;
    previousPrimary = Lizzie.leelaz;
    previousSecondary = Lizzie.leelaz2;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.leelaz2 = null;
  }

  private static final class BlockingOutput extends OutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final CountDownLatch writeCompleted = new CountDownLatch(1);
    private final AtomicBoolean writeRanOnEventThread = new AtomicBoolean();

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      writeRanOnEventThread.set(SwingUtilities.isEventDispatchThread());
      writeEntered.countDown();
      try {
        if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
          throw new IOException("test output was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("test output interrupted", interrupted);
      } finally {
        writeCompleted.countDown();
      }
    }
  }
}
