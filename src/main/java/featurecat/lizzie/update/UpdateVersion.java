package featurecat.lizzie.update;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateVersion implements Comparable<UpdateVersion> {
  private static final Pattern NEXT_RELEASE_PATTERN =
      Pattern.compile("^next-(\\d{4})-(\\d{2})-(\\d{2})\\.(\\d+)$");

  public final String tag;
  public final LocalDate date;
  public final int serial;

  private UpdateVersion(String tag, LocalDate date, int serial) {
    this.tag = tag;
    this.date = date;
    this.serial = serial;
  }

  public static boolean isPackagedRelease(String value) {
    return parse(value) != null;
  }

  public static boolean shouldSkipAutomaticCheck(String currentVersion) {
    return !isPackagedRelease(currentVersion);
  }

  public static int compareReleaseTags(String left, String right) {
    UpdateVersion leftVersion = parse(left);
    UpdateVersion rightVersion = parse(right);
    if (leftVersion == null && rightVersion == null) {
      return 0;
    }
    if (leftVersion == null) {
      return -1;
    }
    if (rightVersion == null) {
      return 1;
    }
    return leftVersion.compareTo(rightVersion);
  }

  public static boolean isNewerThan(String candidate, String current) {
    return compareReleaseTags(candidate, current) > 0;
  }

  public static UpdateVersion parse(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    Matcher matcher = NEXT_RELEASE_PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      return null;
    }
    try {
      LocalDate date =
          LocalDate.of(
              Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(3)));
      int serial = Integer.parseInt(matcher.group(4));
      return new UpdateVersion(trimmed, date, serial);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public int compareTo(UpdateVersion other) {
    int dateCompare = date.compareTo(other.date);
    if (dateCompare != 0) {
      return dateCompare;
    }
    return Integer.compare(serial, other.serial);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof UpdateVersion)) {
      return false;
    }
    UpdateVersion other = (UpdateVersion) obj;
    return serial == other.serial && Objects.equals(date, other.date) && Objects.equals(tag, other.tag);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tag, date, serial);
  }
}
