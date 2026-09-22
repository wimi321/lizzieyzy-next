package featurecat.lizzie.util;

import featurecat.lizzie.logging.SgfObservation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes a captured SGF without exposing a truncated destination, even when replacement fails. */
public final class AtomicSgfFileWriter {
  private AtomicSgfFileWriter() {}

  @FunctionalInterface
  interface Replacement {
    void replace(Path staged, Path target) throws IOException;
  }

  public static void write(Path target, String sgf) throws IOException {
    write(
        target,
        sgf,
        (staged, destination) ->
            Files.move(
                staged,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING));
  }

  static void write(Path target, String sgf, Replacement replacement) throws IOException {
    Path destination = target.toAbsolutePath().normalize();
    Path staged = null;
    try {
      staged = Files.createTempFile(destination.getParent(), ".lizzie-sgf-", ".tmp");
      try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(sgf);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      // Do not fall back to a delete/copy or non-atomic replace: a failed save must retain
      // the original. Unsupported filesystems report a normal save failure instead.
      replacement.replace(staged, destination);
      SgfObservation.record("save", "ok", destination.toString(), null);
    } catch (IOException | RuntimeException failure) {
      SgfObservation.record("save", "failed", destination.toString(), failure);
      throw failure;
    } finally {
      if (staged != null) Files.deleteIfExists(staged);
    }
  }
}
