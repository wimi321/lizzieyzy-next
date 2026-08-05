package featurecat.lizzie.update;

import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** A mirror-aware downloader that safely resumes verified assets from a persistent .part file. */
public final class ResumableDownloader {
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 30000;
  private static final int BUFFER_SIZE = 1024 * 1024;

  public interface ProgressListener {
    void onProgress(Progress progress);
  }

  public enum State {
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    RETRYING,
    COMPLETE
  }

  public static final class Progress {
    public final State state;
    public final String assetName;
    public final String sourceName;
    public final long completedBytes;
    public final long totalBytes;
    public final long bytesPerSecond;
    public final long estimatedSeconds;

    Progress(
        State state,
        String assetName,
        String sourceName,
        long completedBytes,
        long totalBytes,
        long bytesPerSecond,
        long estimatedSeconds) {
      this.state = state;
      this.assetName = assetName;
      this.sourceName = sourceName;
      this.completedBytes = completedBytes;
      this.totalBytes = totalBytes;
      this.bytesPerSecond = bytesPerSecond;
      this.estimatedSeconds = estimatedSeconds;
    }
  }

  public static final class Control {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    public void pause() {
      paused.set(true);
    }

    public void resume() {
      paused.set(false);
      synchronized (pauseLock) {
        pauseLock.notifyAll();
      }
    }

    public void cancel() {
      cancelled.set(true);
      resume();
    }

    public boolean isPaused() {
      return paused.get();
    }

    public boolean isCancelled() {
      return cancelled.get();
    }

    void awaitIfPaused(ProgressListener listener, DownloadSpec spec, String source, long completed)
        throws DownloadCancelledException {
      if (!paused.get()) {
        checkCancelled();
        return;
      }
      notifyProgress(listener, State.PAUSED, spec, source, completed, 0L, -1L);
      synchronized (pauseLock) {
        while (paused.get() && !cancelled.get()) {
          try {
            pauseLock.wait(250L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadCancelledException("Update download was interrupted.");
          }
        }
      }
      checkCancelled();
    }

    void checkCancelled() throws DownloadCancelledException {
      if (cancelled.get()) {
        throw new DownloadCancelledException("Update download was cancelled.");
      }
    }
  }

  public static final class DownloadSpec {
    public final String assetName;
    public final long sizeBytes;
    public final String sha256;
    public final List<String> urls;

    public DownloadSpec(String assetName, long sizeBytes, String sha256, List<String> urls) {
      if (UpdateManifest.isBlank(assetName)
          || sizeBytes <= 0L
          || UpdateManifest.isBlank(sha256)
          || urls == null
          || urls.isEmpty()) {
        throw new IllegalArgumentException("Complete download metadata is required.");
      }
      this.assetName = assetName;
      this.sizeBytes = sizeBytes;
      this.sha256 = sha256.toLowerCase(Locale.ROOT);
      this.urls = List.copyOf(urls);
    }

    public static DownloadSpec from(UpdateManifest.Component component) {
      return new DownloadSpec(
          component.assetName, component.sizeBytes, component.sha256, component.downloadUrls());
    }

    public static DownloadSpec from(UpdateManifest.PackageAsset packageAsset) {
      return new DownloadSpec(
          packageAsset.assetName,
          packageAsset.sizeBytes,
          packageAsset.sha256,
          packageAsset.downloadUrls());
    }
  }

  public static final class DownloadCancelledException extends IOException {
    DownloadCancelledException(String message) {
      super(message);
    }
  }

  public Path download(DownloadSpec spec, Path output, Control control, ProgressListener listener)
      throws IOException {
    if (control == null) {
      control = new Control();
    }
    Path normalizedOutput = output.toAbsolutePath().normalize();
    Path parent = normalizedOutput.getParent();
    if (parent == null) {
      throw new IOException("Update download destination has no parent directory.");
    }
    Files.createDirectories(parent);
    Path part = normalizedOutput.resolveSibling(normalizedOutput.getFileName() + ".part");

    if (isVerified(normalizedOutput, spec)) {
      notifyProgress(
          listener, State.COMPLETE, spec, sourceName(spec.urls.get(0)), spec.sizeBytes, 0L, 0L);
      return normalizedOutput;
    }
    Files.deleteIfExists(normalizedOutput);
    if (Files.isRegularFile(part) && Files.size(part) > spec.sizeBytes) {
      Files.delete(part);
    }

    List<IOException> failures = new ArrayList<>();
    for (int index = 0; index < spec.urls.size(); index++) {
      String url = spec.urls.get(index);
      String source = sourceName(url);
      control.checkCancelled();
      if (index > 0) {
        notifyProgress(
            listener,
            State.RETRYING,
            spec,
            source,
            Files.exists(part) ? Files.size(part) : 0L,
            0L,
            -1L);
      }
      try {
        downloadFromSource(spec, url, source, part, control, listener);
        control.checkCancelled();
        notifyProgress(listener, State.VERIFYING, spec, source, spec.sizeBytes, 0L, 0L);
        verify(part, spec);
        Files.move(part, normalizedOutput, StandardCopyOption.REPLACE_EXISTING);
        notifyProgress(listener, State.COMPLETE, spec, source, spec.sizeBytes, 0L, 0L);
        return normalizedOutput;
      } catch (DownloadCancelledException e) {
        throw e;
      } catch (IOException e) {
        failures.add(new IOException(source + ": " + e.getMessage(), e));
        if (isIntegrityFailure(e)) {
          Files.deleteIfExists(part);
        }
      }
    }
    IOException failure =
        new IOException("All update download sources failed for " + spec.assetName);
    failures.forEach(failure::addSuppressed);
    throw failure;
  }

  private void downloadFromSource(
      DownloadSpec spec,
      String url,
      String source,
      Path part,
      Control control,
      ProgressListener listener)
      throws IOException {
    long offset = Files.isRegularFile(part) ? Files.size(part) : 0L;
    if (offset == spec.sizeBytes) {
      return;
    }
    notifyProgress(listener, State.CONNECTING, spec, source, offset, 0L, -1L);
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("User-Agent", "LizzieYzy-Next-Updater");
      if (offset > 0L) {
        connection.setRequestProperty("Range", "bytes=" + offset + "-");
      }
      int code = connection.getResponseCode();
      boolean append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL;
      if (code == 416 && offset == spec.sizeBytes) {
        return;
      }
      if (code < 200 || code >= 300) {
        throw new IOException("HTTP " + code + " while downloading " + spec.assetName);
      }
      if (append) {
        validateContentRange(connection.getHeaderField("Content-Range"), offset);
      } else if (offset > 0L) {
        offset = 0L;
      }

      StandardOpenOption[] options =
          append
              ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
              : new StandardOpenOption[] {
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
              };
      long startedAt = System.nanoTime();
      long sourceStart = offset;
      long completed = offset;
      try (InputStream input = connection.getInputStream();
          OutputStream output = Files.newOutputStream(part, options)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          control.awaitIfPaused(listener, spec, source, completed);
          if (read == 0) {
            continue;
          }
          output.write(buffer, 0, read);
          completed += read;
          if (completed > spec.sizeBytes) {
            throw new IntegrityException("Downloaded file is larger than the signed size.");
          }
          long elapsedNanos = Math.max(1L, System.nanoTime() - startedAt);
          long transferred = completed - sourceStart;
          long bytesPerSecond =
              transferred <= 0L ? 0L : (long) (transferred * 1_000_000_000.0 / elapsedNanos);
          long eta =
              bytesPerSecond <= 0L
                  ? -1L
                  : Math.max(0L, (spec.sizeBytes - completed) / bytesPerSecond);
          notifyProgress(listener, State.DOWNLOADING, spec, source, completed, bytesPerSecond, eta);
        }
      }
      if (completed != spec.sizeBytes) {
        throw new IOException(
            "Download ended early (" + completed + " of " + spec.sizeBytes + " bytes).");
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid update download URL.", e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static void validateContentRange(String contentRange, long expectedOffset)
      throws IOException {
    String expectedPrefix = "bytes " + expectedOffset + "-";
    if (contentRange == null || !contentRange.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
      throw new IOException("Server returned an invalid resume range.");
    }
  }

  private static boolean isVerified(Path file, DownloadSpec spec) throws IOException {
    return Files.isRegularFile(file)
        && Files.size(file) == spec.sizeBytes
        && spec.sha256.equalsIgnoreCase(sha256(file));
  }

  private static void verify(Path file, DownloadSpec spec) throws IOException {
    if (!Files.isRegularFile(file) || Files.size(file) != spec.sizeBytes) {
      throw new IntegrityException("Downloaded file size does not match the signed manifest.");
    }
    if (!spec.sha256.equalsIgnoreCase(sha256(file))) {
      throw new IntegrityException("Downloaded file SHA-256 does not match the signed manifest.");
    }
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) {
            digest.update(buffer, 0, read);
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable.", e);
    }
  }

  static String sourceName(String url) {
    try {
      String host = URI.create(url).getHost();
      if (host == null) {
        return "download source";
      }
      if (host.equalsIgnoreCase("download.goagent.top")) {
        return "Cloudflare R2";
      }
      if (host.equalsIgnoreCase("github.com") || host.endsWith(".githubusercontent.com")) {
        return "GitHub";
      }
      return host;
    } catch (RuntimeException e) {
      return "download source";
    }
  }

  public static String formatDuration(long seconds) {
    if (seconds < 0L) {
      return "--";
    }
    Duration duration = Duration.ofSeconds(seconds);
    long hours = duration.toHours();
    long minutes = duration.minusHours(hours).toMinutes();
    long remainingSeconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds();
    if (hours > 0L) {
      return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
    }
    return String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
  }

  private static void notifyProgress(
      ProgressListener listener,
      State state,
      DownloadSpec spec,
      String source,
      long completed,
      long bytesPerSecond,
      long eta) {
    if (listener != null) {
      listener.onProgress(
          new Progress(
              state, spec.assetName, source, completed, spec.sizeBytes, bytesPerSecond, eta));
    }
  }

  static boolean isIntegrityFailure(Throwable exception) {
    if (exception == null) {
      return false;
    }
    if (exception instanceof IntegrityException) {
      return true;
    }
    if (isIntegrityFailure(exception.getCause())) {
      return true;
    }
    for (Throwable suppressed : exception.getSuppressed()) {
      if (isIntegrityFailure(suppressed)) {
        return true;
      }
    }
    return false;
  }

  static final class IntegrityException extends IOException {
    IntegrityException(String message) {
      super(message);
    }
  }
}
