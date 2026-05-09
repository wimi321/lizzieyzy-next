package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;

/**
 * 给 EngineFollowController 用的引擎指令出口。生产实现包装 Lizzie.leelaz； 测试实现记录调用序列。所有方法可能阻塞（GTP 回环），调用方负责异步派发。
 */
public interface EngineCommandSink {
  void playMove(Stone color, String coord);

  void undo();

  void clear();

  void clearBestMoves();

  /** 触发引擎按当前 BoardHistoryList 重 sync（forceResync 兜底用，等价 loadsgf 路径）。 */
  void resyncFromCurrentHistory(BoardHistoryNode target);
}
