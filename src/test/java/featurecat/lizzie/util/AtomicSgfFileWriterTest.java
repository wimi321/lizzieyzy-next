package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicSgfFileWriterTest {
  @TempDir Path directory;

  @Test
  void writesAndReplacesCompleteUtf8ContentInChinesePath() throws Exception {
    Path target = directory.resolve("中文棋谱.SGF");
    AtomicSgfFileWriter.write(target, "(;SZ[19]C[原棋谱])");
    AtomicSgfFileWriter.write(target, "(;SZ[19]C[后来的完整棋谱];B[aa])");
    assertEquals("(;SZ[19]C[后来的完整棋谱];B[aa])", Files.readString(target));
    assertOnlyDestinationExists(target);
  }

  @Test
  void failedReplacementPreservesOriginalAndRemovesCompleteTemporaryFile() throws Exception {
    Path target = directory.resolve("protected.sgf");
    Files.writeString(target, "original");
    assertThrows(
        IOException.class,
        () ->
            AtomicSgfFileWriter.write(
                target,
                "snapshot",
                (staged, dest) -> {
                  assertEquals("snapshot", Files.readString(staged));
                  assertEquals("original", Files.readString(dest));
                  throw new IOException("Simulated locked target");
                }));
    assertEquals("original", Files.readString(target));
    assertOnlyDestinationExists(target);
  }

  @Test
  void unsupportedAtomicReplacementDoesNotFallBackToTruncatingTheOriginal() throws Exception {
    Path target = directory.resolve("protected.sgf");
    Files.writeString(target, "original");
    assertThrows(
        AtomicMoveNotSupportedException.class,
        () ->
            AtomicSgfFileWriter.write(
                target,
                "snapshot",
                (staged, dest) -> {
                  throw new AtomicMoveNotSupportedException(
                      staged.toString(), dest.toString(), "unsupported");
                }));
    assertEquals("original", Files.readString(target));
    assertOnlyDestinationExists(target);
  }

  @Test
  void missingParentDoesNotCreateAPartialDestination() {
    Path target = directory.resolve("missing/game.sgf");
    assertThrows(IOException.class, () -> AtomicSgfFileWriter.write(target, "snapshot"));
    assertFalse(Files.exists(target));
  }

  private void assertOnlyDestinationExists(Path target) throws IOException {
    try (var files = Files.list(directory)) {
      assertEquals(java.util.List.of(target), files.toList());
    }
  }
}
