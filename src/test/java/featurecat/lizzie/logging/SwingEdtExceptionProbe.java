package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

public final class SwingEdtExceptionProbe {
  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    CrashHandlers.install();
    CountDownLatch continued = new CountDownLatch(1);
    SwingUtilities.invokeLater(
        () -> {
          throw new IllegalStateException("edt-canary");
        });
    SwingUtilities.invokeLater(continued::countDown);
    if (!continued.await(10, TimeUnit.SECONDS)) {
      System.exit(3);
    }
    runtime.awaitIdle();
    System.out.println("CONTINUED");
  }
}
