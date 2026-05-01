package featurecat.lizzie.analysis;

import java.io.File;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ReadBoardUpdateRequest {
  private static final String COMMAND = "readboardUpdateReady";
  private static final Pattern VERSION_TAG_PATTERN = Pattern.compile("^v\\d+\\.\\d+\\.\\d+$");
  private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN =
      Pattern.compile("^[A-Za-z]:[\\\\/].+");
  private static final Pattern WINDOWS_UNC_PATH_PATTERN = Pattern.compile("^\\\\\\\\.+");

  private final String versionTag;
  private final File zipPath;

  private ReadBoardUpdateRequest(String versionTag, File zipPath) {
    this.versionTag = versionTag;
    this.zipPath = zipPath;
  }

  static ReadBoardUpdateRequest tryParse(String rawLine) {
    if (rawLine == null) {
      return null;
    }
    String line = rawLine.replace("\r", "").replace("\n", "");
    String[] parts = line.split("\t", -1);
    if (parts.length != 3 || !COMMAND.equals(parts[0])) {
      return null;
    }
    String versionTag = parts[1];
    String zipPathText = parts[2];
    if (!VERSION_TAG_PATTERN.matcher(versionTag).matches() || zipPathText.isEmpty()) {
      return null;
    }
    File zipPath = new File(zipPathText);
    if (!isAbsolutePath(zipPathText, zipPath)) {
      return null;
    }
    return new ReadBoardUpdateRequest(versionTag, zipPath.getAbsoluteFile());
  }

  private static boolean isAbsolutePath(String rawPath, File file) {
    return file.isAbsolute()
        || WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(rawPath).matches()
        || WINDOWS_UNC_PATH_PATTERN.matcher(rawPath).matches();
  }

  public String versionTag() {
    return versionTag;
  }

  public File zipPath() {
    return zipPath;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ReadBoardUpdateRequest)) {
      return false;
    }
    ReadBoardUpdateRequest that = (ReadBoardUpdateRequest) other;
    return versionTag.equals(that.versionTag) && zipPath.equals(that.zipPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(versionTag, zipPath);
  }
}
