package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.Utils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureTsumeGoHelperJarTest {
  private static final String HELPER_DIR = "captureTsumeGo";
  private static final String HELPER_JAR = "CaptureTsumeGo1.2.jar";

  @Test
  void neverLooksUnderFilesystemRootWhenCwdIsRoot(@TempDir Path workDir) throws Exception {
    File workDirectory = workDir.toFile().getCanonicalFile();
    Path expected = workDirectory.toPath().resolve(HELPER_DIR).resolve(HELPER_JAR);
    File forbiddenRootLookup =
        new File(new File(File.separator, HELPER_DIR), HELPER_JAR).getAbsoluteFile();

    String previousUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", File.separator);
    try {
      File resolved = CaptureTsumeGo.helperJarFile(workDirectory).getCanonicalFile();

      assertEquals(expected, resolved.toPath());
      assertNotEquals(forbiddenRootLookup.getAbsolutePath(), resolved.getAbsolutePath());
      assertTrue(resolved.toPath().startsWith(workDirectory.toPath()), resolved.getAbsolutePath());

      Utils.copyCaptureTsumeGo(workDirectory);

      assertTrue(Files.isRegularFile(expected), expected.toString());
      assertTrue(Files.size(expected) > 0);
      assertFalse(
          Files.exists(forbiddenRootLookup.toPath()), forbiddenRootLookup.getAbsolutePath());
    } finally {
      System.setProperty("user.dir", previousUserDir);
    }
  }

  @Test
  void extractFailureIsThrownInsteadOfSwallowed(@TempDir Path workDir) throws Exception {
    Files.writeString(workDir.resolve(HELPER_DIR), "not-a-directory");
    IOException thrown =
        assertThrows(IOException.class, () -> Utils.copyCaptureTsumeGo(workDir.toFile()));
    assertFalse(thrown.getMessage() == null || thrown.getMessage().isBlank());
  }
}
