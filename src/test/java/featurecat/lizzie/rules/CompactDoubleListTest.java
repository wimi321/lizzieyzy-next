package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactDoubleListTest {
  @Test
  void preservesDoubleValuesAndIsImmutable() {
    List<Double> source = new ArrayList<>(List.of(-1.0, 0.125, Math.PI, 1.0));
    List<Double> compact = CompactDoubleList.copyOf(source);

    assertEquals(source, compact);
    assertThrows(UnsupportedOperationException.class, () -> compact.set(0, 0.0));
  }

  @Test
  void copyDoesNotSharePrimitiveStorage() {
    List<Double> first = CompactDoubleList.copyOf(List.of(1.0, 2.0));
    List<Double> second = CompactDoubleList.copyOf(first);

    assertNotSame(first, second);
    assertEquals(first, second);
  }

  @Test
  void preservesRareNullValues() {
    List<Double> source = Arrays.asList(1.0, null, Double.NaN);

    assertEquals(source, CompactDoubleList.copyOf(source));
  }
}
