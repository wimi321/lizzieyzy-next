package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.Leelaz;
import java.util.ResourceBundle;

/**
 * Single presentation mapping from sealed State or exact history record. Readers do not copy
 * boolean compatibility logic or read a second current-state bag.
 */
public final class EngineGamePresentation {
  private EngineGamePresentation() {}

  public static EngineGameSnapshot current() {
    return Lizzie.engineGame == null ? new EngineGameSnapshot.Idle() : Lizzie.engineGame.current();
  }

  public static boolean showLiveBatchScores(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return snapshot != null && snapshot.playing() && view != null && view.batch();
  }

  public static int blackWins(EngineGameSnapshot snapshot) {
    if (!(snapshot instanceof EngineGameSnapshot.BatchActive active)) {
      return 0;
    }
    EngineGameView view = snapshot.view();
    if (view != null && !view.firstIsBlack()) {
      return active.batch().secondWins();
    }
    return active.batch().firstWins();
  }

  public static int whiteWins(EngineGameSnapshot snapshot) {
    if (!(snapshot instanceof EngineGameSnapshot.BatchActive active)) {
      return 0;
    }
    EngineGameView view = snapshot.view();
    if (view != null && !view.firstIsBlack()) {
      return active.batch().firstWins();
    }
    return active.batch().secondWins();
  }

  public static EngineGameRecordContext historyContext(GameInfo info) {
    if (info == null) {
      return null;
    }
    if (info.engineGameRecord() != null) {
      return info.engineGameRecord().context();
    }
    return info.engineGameRecordContext();
  }

  public static EngineGameParticipantDescriptor blackDescriptor(GameInfo info) {
    EngineGameRecordContext context = historyContext(info);
    if (context != null) {
      return context.black();
    }
    return null;
  }

  public static EngineGameParticipantDescriptor whiteDescriptor(GameInfo info) {
    EngineGameRecordContext context = historyContext(info);
    if (context != null) {
      return context.white();
    }
    return null;
  }

  public static boolean blackKatago(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = blackDescriptor(info);
    if (descriptor != null) {
      return descriptor.katago();
    }
    Leelaz engine = blackEngine(live);
    return engine != null && engine.isKatago;
  }

  public static boolean whiteKatago(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = whiteDescriptor(info);
    if (descriptor != null) {
      return descriptor.katago();
    }
    Leelaz engine = whiteEngine(live);
    return engine != null && engine.isKatago;
  }

  public static boolean blackSai(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = blackDescriptor(info);
    if (descriptor != null) {
      return descriptor.sai();
    }
    Leelaz engine = blackEngine(live);
    return engine != null && engine.isSai;
  }

  public static boolean whiteSai(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = whiteDescriptor(info);
    if (descriptor != null) {
      return descriptor.sai();
    }
    Leelaz engine = whiteEngine(live);
    return engine != null && engine.isSai;
  }

  public static Leelaz engine(int index) {
    EngineManager manager = Lizzie.engineManager;
    if (manager == null
        || manager.engineList == null
        || index < 0
        || index >= manager.engineList.size()) {
      return null;
    }
    return manager.engineList.get(index);
  }

  public static Leelaz blackEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.blackIndex());
  }

  public static Leelaz whiteEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.whiteIndex());
  }

  public static Leelaz firstEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.firstIndex());
  }

  public static Leelaz secondEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.secondIndex());
  }

  public static Leelaz sideToMoveEngine(EngineGameSnapshot snapshot, boolean blackToPlay) {
    return blackToPlay ? blackEngine(snapshot) : whiteEngine(snapshot);
  }

  public static boolean participantHasKataGoPda(EngineGameSnapshot snapshot) {
    Leelaz first = firstEngine(snapshot);
    Leelaz second = secondEngine(snapshot);
    return (first != null && first.isKataGoPda) || (second != null && second.isKataGoPda);
  }

  public static GameInfo currentHistoryInfo() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getGameInfo();
  }

  public static String matchRulesCaption(
      EngineGameSnapshot snapshot, MatchRulesSnapshot live, GameInfo info, ResourceBundle bundle) {
    if (bundle == null) {
      return "";
    }
    MatchRulesSnapshot inspectable = inspectableMatchRules(snapshot, live, info);
    return inspectable == null ? "" : inspectable.mainSummary(bundle);
  }

  public static MatchRulesSnapshot historyMatchRules(GameInfo info) {
    if (info == null) {
      return null;
    }
    if (info.engineGameRecord() != null && info.engineGameRecord().matchRules() != null) {
      return info.engineGameRecord().matchRules();
    }
    if (info.engineGameRecordContext() != null
        && info.engineGameRecordContext().matchRules() != null) {
      return info.engineGameRecordContext().matchRules();
    }
    if (info.engineGameSaveSnapshot() != null
        && info.engineGameSaveSnapshot().context() != null
        && info.engineGameSaveSnapshot().context().matchRules() != null) {
      return info.engineGameSaveSnapshot().context().matchRules();
    }
    return null;
  }

  public static MatchRulesSnapshot inspectableMatchRules(
      EngineGameSnapshot snapshot, MatchRulesSnapshot live, GameInfo info) {
    MatchRulesSnapshot history = historyMatchRules(info);
    if (history != null) {
      return history;
    }
    if (live == null) {
      return null;
    }
    boolean idle = snapshot instanceof EngineGameSnapshot.Idle;
    if (!idle
        || live.phase() == MatchRulesSnapshot.Phase.FAILED
        || live.phase() == MatchRulesSnapshot.Phase.PREPARING) {
      return live;
    }
    return null;
  }
}
