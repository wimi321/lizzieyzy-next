package featurecat.lizzie.gui;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 单线程后台 worker，专门跑从 CEF onQuery 派发过来的弈客消息处理。
 *
 * <p>用 LinkedBlockingDeque + 限长 + 抛弃最旧策略，避免下游慢时无限堆积拖死 EDT。
 */
final class YikeQueryWorker {
  private static final int CAPACITY = 64;

  private static final ThreadPoolExecutor EXEC =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingDeque<>(CAPACITY),
          r -> {
            Thread t = new Thread(r, "yike-query-worker");
            t.setDaemon(true);
            return t;
          },
          (r, ex) -> {
            // 队列满：丢掉最旧的一个，再塞新的
            LinkedBlockingDeque<Runnable> q = (LinkedBlockingDeque<Runnable>) ex.getQueue();
            q.pollFirst();
            q.offer(r);
          });

  static void submit(Runnable r) {
    try {
      EXEC.execute(r);
    } catch (Throwable ignored) {
    }
  }

  private YikeQueryWorker() {}
}
