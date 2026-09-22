package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameRecord;
import featurecat.lizzie.enginegame.EngineGameRecordContext;
import featurecat.lizzie.enginegame.EngineGameSaveSnapshot;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.Date;
import java.util.Objects;

public class GameInfo {
  public static final String DEFAULT_NAME_CPU_PLAYER = "Computer";
  public static double DEFAULT_KOMI = 7.5;
  public boolean changedKomi = false;

  private String playerBlack = "";
  private String playerWhite = "";
  private Date date = new Date();
  private double komi = DEFAULT_KOMI;
  private int handicap = 0;
  private String result = "";
  private EngineGameRecordContext engineGameRecordContext;
  private EngineGameRecord engineGameRecord;
  private EngineGameSaveSnapshot engineGameSaveSnapshot;


  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
    Lizzie.frame.setResult(result);
  }

  public String getPlayerBlack() {
    return playerBlack;
  }

  public Date getDate() {
    return date;
  }

  public void setDate(Date date) {
    this.date = date;
  }

  public void setPlayerBlack(String playerBlack) {
    this.playerBlack = playerBlack;
  }

  public String getPlayerWhite() {
    return playerWhite;
  }

  public String getSaveFileName() {
    if (playerBlack.equals("") && playerWhite.equals(""))
      return Lizzie.resourceBundle.getString("GameInfo.untitled");
    else return playerBlack + "_Vs_" + playerWhite;
  }

  public void setPlayerWhite(String playerWhite) {
    this.playerWhite = playerWhite;
  }

  public double getKomi() {
    return komi;
  }

  public void setKomi(double komi) {
    this.komi = komi;
    if (LizzieFrame.menu != null && LizzieFrame.menu.txtKomi != null) {
      LizzieFrame.menu.txtKomi.setText(String.valueOf(komi));
    }
    if (Lizzie.frame != null && Lizzie.frame.isInScoreMode && Lizzie.board != null) {
      Lizzie.board.showGroupResult();
    }
  }

  public void setKomiNoMenu(double komi) {
    this.komi = komi;
  }

  public void changeKomi() {
    changedKomi = true;
  }

  public int getHandicap() {
    return handicap;
  }

  public void setHandicap(int handicap) {
    this.handicap = handicap;
  }

  public boolean hasEngineGameHistory() {
    return engineGameRecord != null
        || engineGameRecordContext != null
        || engineGameSaveSnapshot != null;
  }

  public EngineGameRecordContext engineGameRecordContext() {
    return engineGameRecordContext;
  }

  public EngineGameRecord engineGameRecord() {
    return engineGameRecord;
  }

  public EngineGameSaveSnapshot engineGameSaveSnapshot() {
    return engineGameSaveSnapshot;
  }

  public void attachEngineGameRecordContext(EngineGameRecordContext context) {
    if (engineGameRecord != null) {
      return;
    }
    this.engineGameRecordContext = Objects.requireNonNull(context, "context");
  }

  public void freezeEngineGameRecord(EngineGameRecord record) {
    if (engineGameRecord != null) {
      return;
    }
    this.engineGameRecord = Objects.requireNonNull(record, "record");
    if (engineGameRecordContext == null) {
      engineGameRecordContext = record.context();
    }
  }

  public void setEngineGameSaveSnapshot(EngineGameSaveSnapshot snapshot) {
    this.engineGameSaveSnapshot = snapshot;
  }

  public void copyEngineGameHistoryFrom(GameInfo original) {
    if (original == null) {
      return;
    }
    engineGameRecordContext = original.engineGameRecordContext;
    engineGameRecord = original.engineGameRecord;
    engineGameSaveSnapshot = original.engineGameSaveSnapshot;
  }

  /** Detached save metadata; unlike setters, this must not call the UI while history is locked. */
  public GameInfo copyForSave() {
    GameInfo copy = new GameInfo();
    copy.playerBlack = playerBlack;
    copy.playerWhite = playerWhite;
    copy.date = date == null ? null : new Date(date.getTime());
    copy.komi = komi;
    copy.changedKomi = changedKomi;
    copy.handicap = handicap;
    copy.result = result;
    copy.copyEngineGameHistoryFrom(this);
    return copy;
  }

  public void clearEngineGameHistory() {
    engineGameRecordContext = null;
    engineGameRecord = null;
    engineGameSaveSnapshot = null;
  }

  public void resetAllNoKomi() {
    this.handicap = 0;
    this.playerBlack = "";
    this.playerWhite = "";
    this.date = new Date();
    this.result = "";
    clearEngineGameHistory();
    Lizzie.frame.setResult("");
  }
}
