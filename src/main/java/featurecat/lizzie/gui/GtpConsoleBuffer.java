package featurecat.lizzie.gui;

import featurecat.lizzie.util.DocType;
import java.util.ArrayDeque;

final class GtpConsoleBuffer {
  static final int CAPACITY = 4096;
  private final ArrayDeque<DocType> queue = new ArrayDeque<>();

  void offer(DocType doc) {
    if (doc == null) {
      return;
    }
    synchronized (queue) {
      while (queue.size() >= CAPACITY) {
        queue.removeFirst();
      }
      queue.addLast(doc);
    }
  }

  DocType poll() {
    synchronized (queue) {
      return queue.pollFirst();
    }
  }

  int size() {
    synchronized (queue) {
      return queue.size();
    }
  }
}
