package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KataRawOwnershipParserTest {

  @Test
  void extractsAndConvertsWhiteOwnershipToBlackPerspective() {
    KataRawOwnershipParser parser = new KataRawOwnershipParser();
    parser.begin(3, 2);

    assertTrue(parser.accept("policy").isEmpty());
    assertTrue(parser.accept("whiteOwnership").isEmpty());
    assertTrue(parser.accept("0.5 -0.25 bad").isEmpty());
    Optional<List<Double>> result = parser.accept("-1 0 0.75");

    assertTrue(result.isPresent());
    assertEquals(List.of(-0.5, 0.25, 0.0, 1.0, -0.0, -0.75), result.get());
  }

  @Test
  void aMalformedRowCancelsTheCurrentResponse() {
    KataRawOwnershipParser parser = new KataRawOwnershipParser();
    parser.begin(2, 2);
    parser.accept("whiteOwnership");
    parser.accept("0.1");

    assertTrue(parser.accept("0.1 0.2").isEmpty());
    assertTrue(parser.accept("0.3 0.4").isEmpty());
  }
}
