package featurecat.lizzie.gui;

public class YikeUrlInfo {
  public static final int TYPE_OLD_LIVE_ROOM = 1;
  public static final int TYPE_OLD_LIVE_BOARD = 2;
  public static final int TYPE_GAME_ROOM = 5;
  public static final int TYPE_NEW_LIVE_ROOM = 6;
  public static final int TYPE_UNITE_ROOM = 7;

  private final int type;
  private final String id;
  private final long roomId;
  private final String ajaxUrl;

  public YikeUrlInfo(int type, String id, long roomId, String ajaxUrl) {
    this.type = type;
    this.id = id;
    this.roomId = roomId;
    this.ajaxUrl = ajaxUrl;
  }

  public int getType() {
    return type;
  }

  public String getId() {
    return id;
  }

  public long getRoomId() {
    return roomId;
  }

  public String getAjaxUrl() {
    return ajaxUrl;
  }
}
