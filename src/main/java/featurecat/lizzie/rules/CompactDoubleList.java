package featurecat.lizzie.rules;

import java.util.AbstractList;
import java.util.BitSet;
import java.util.List;
import java.util.RandomAccess;

/** Immutable primitive-backed list used for per-node ownership data. */
final class CompactDoubleList extends AbstractList<Double> implements RandomAccess {
  private final double[] values;
  private final BitSet nullValues;

  private CompactDoubleList(double[] values, BitSet nullValues) {
    this.values = values;
    this.nullValues = nullValues;
  }

  static List<Double> copyOf(List<Double> source) {
    if (source == null) {
      return null;
    }
    if (source instanceof CompactDoubleList) {
      CompactDoubleList compact = (CompactDoubleList) source;
      return new CompactDoubleList(
          compact.values.clone(),
          compact.nullValues == null ? null : (BitSet) compact.nullValues.clone());
    }
    double[] values = new double[source.size()];
    BitSet nullValues = null;
    for (int i = 0; i < values.length; i++) {
      Double value = source.get(i);
      if (value == null) {
        if (nullValues == null) {
          nullValues = new BitSet(values.length);
        }
        nullValues.set(i);
      } else {
        values[i] = value;
      }
    }
    return new CompactDoubleList(values, nullValues);
  }

  static List<Double> copyOf(double[] source, int size) {
    if (source == null) {
      return null;
    }
    if (size < 0 || size > source.length) {
      throw new IndexOutOfBoundsException("size=" + size + ", length=" + source.length);
    }
    return new CompactDoubleList(java.util.Arrays.copyOf(source, size), null);
  }

  @Override
  public Double get(int index) {
    return nullValues != null && nullValues.get(index) ? null : values[index];
  }

  @Override
  public int size() {
    return values.length;
  }
}
