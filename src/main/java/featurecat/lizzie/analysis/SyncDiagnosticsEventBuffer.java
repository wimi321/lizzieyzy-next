package featurecat.lizzie.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

final class SyncDiagnosticsEventBuffer<T> {
  private final int capacity;
  private final Deque<T> values = new ArrayDeque<>();

  SyncDiagnosticsEventBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  synchronized void add(T value) {
    if (value == null) {
      return;
    }
    if (values.size() == capacity) {
      values.removeFirst();
    }
    values.addLast(value);
  }

  synchronized List<T> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }

  synchronized void clear() {
    values.clear();
  }
}
