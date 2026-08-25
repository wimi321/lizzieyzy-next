package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class ReadBoardEdtCommandDispatchTest {
  @Test
  void blockedReadBoardOutputNeverBlocksTheSwingEventThread() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    BlockingOutput output = new BlockingOutput();
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", new BufferedOutputStream(output));

      CountDownLatch sendReturned = new CountDownLatch(1);
      CountDownLatch nextUiEventRan = new CountDownLatch(1);
      SwingUtilities.invokeLater(
          () -> {
            readBoard.sendCommand("place 1 1");
            sendReturned.countDown();
          });

      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS), "readboard write never started");
      SwingUtilities.invokeLater(nextUiEventRan::countDown);

      assertTrue(
          sendReturned.await(1, TimeUnit.SECONDS),
          "readboard output blocked the Swing event thread");
      assertTrue(
          nextUiEventRan.await(1, TimeUnit.SECONDS),
          "the next user input could not run while readboard output was stalled");
      assertFalse(output.writeRanOnEventThread.get(), "physical readboard output ran on the EDT");
    } finally {
      output.releaseWrite.countDown();
      output.writeCompleted.await(2, TimeUnit.SECONDS);
      Lizzie.frame = previousFrame;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((Unsafe) field.get(null)).allocateInstance(type);
  }

  private static void setField(ReadBoard target, String name, Object value) throws Exception {
    Field field = ReadBoard.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
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
