package featurecat.lizzie.teacher;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Literal local addresses accepted for a user-configured HTTP AI service, without DNS lookup. */
final class TeacherServiceHost {
  private TeacherServiceHost() {}

  static boolean allowsPlainHttp(String host) {
    if (host == null) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    String literal =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (literal.contains(":")) {
      // Only IPv6 literals reach InetAddress: never resolve a hostname, scoped address or mapped
      // IPv4 notation. This also excludes link-local, unspecified and multicast destinations.
      if (!literal.matches("[0-9a-fA-F:]+")) {
        return false;
      }
      try {
        InetAddress address = InetAddress.getByName(literal);
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (address.isLoopbackAddress() || (bytes[0] & 0xfe) == 0xfc);
      } catch (UnknownHostException invalidLiteral) {
        return false;
      }
    }
    String[] octets = literal.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    int[] address = new int[4];
    for (int i = 0; i < octets.length; i++) {
      if (!octets[i].matches("0|[1-9][0-9]{0,2}")) {
        return false;
      }
      address[i] = Integer.parseInt(octets[i]);
      if (address[i] > 255) {
        return false;
      }
    }
    return (address[0] == 127 && address[1] == 0 && address[2] == 0 && address[3] == 1)
        || address[0] == 10
        || (address[0] == 172 && address[1] >= 16 && address[1] <= 31)
        || (address[0] == 192 && address[1] == 168);
  }
}
