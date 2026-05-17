package featurecat.lizzie.rules;

import static java.util.Arrays.asList;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.EncodingDetector;
import featurecat.lizzie.util.Utils;
import java.io.*;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class SGFParser {
  private static final SimpleDateFormat SGF_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
  private static final int DEFAULT_BOARD_SIZE = 19;
  private static final Pattern BOARD_SIZE_PATTERN =
      Pattern.compile("(?s).*?SZ\\[([\\d:]+)\\](?s).*");
  private static final Pattern RECT_BOARD_SIZE_PATTERN = Pattern.compile("([\\d]+):([\\d]+)");
  private static final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String[] listProps =
      new String[] {"LB", "CR", "SQ", "MA", "TR", "AB", "AW", "AE"};
  private static final String[] markupProps = new String[] {"LB", "CR", "SQ", "MA", "TR"};
  private static final Field BLACK_ZOBRIST_FIELD = resolveZobristField("blackZobrist");
  private static final Field WHITE_ZOBRIST_FIELD = resolveZobristField("whiteZobrist");

  private static boolean isExtraMode2 = false;

  // static boolean oriEmpty = false;

  public static boolean load(String filename, boolean showHint) throws IOException {
    // Clear the board
    isExtraMode2 = false;
    Lizzie.board.isLoadingFile = true;
    Lizzie.board.clear(false);
    File file = new File(filename);
    if (!file.exists() || !file.canRead()) {
      return false;
    }

    String encoding = EncodingDetector.detect(filename);
    String value;
    try (FileInputStream fp = new FileInputStream(file);
        InputStreamReader reader =
            new InputStreamReader(fp, encoding.equals("WINDOWS-1252") ? "GB18030" : encoding)) {
      StringBuilder builder = new StringBuilder();
      while (reader.ready()) {
        builder.append((char) reader.read());
      }
      value = builder.toString();
    }
    if (value.isEmpty()) {
      Lizzie.board.isLoadingFile = false;
      return false;
    }

    boolean returnValue = parse(value);
    Lizzie.board.isLoadingFile = false;
    if (Lizzie.board.hasStartStone) {
      int lenth2 = Lizzie.board.startStonelist.size();
      for (int i = 0; i < lenth2; i++) {
        Movelist move = Lizzie.board.startStonelist.get(lenth2 - 1 - i);
        String color = move.isblack ? "b" : "w";
        if (!move.ispass) {
          Lizzie.leelaz.sendCommand(
              "play " + color + " " + Board.convertCoordinatesToName(move.x, move.y));
        }
      }
    }
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            if (Lizzie.config.loadSgfLast)
              while (Lizzie.board.nextMove(false))
                ;
            Lizzie.board.clearAfterMove();
            if (isExtraMode2
                && !Lizzie.config.isDoubleEngineMode()
                && !Lizzie.config.isAutoAna
                && showHint) {
              int ret =
                  JOptionPane.showConfirmDialog(
                      Lizzie.frame,
                      Lizzie.resourceBundle.getString("SGFParse.doubleEngineHint"),
                      Lizzie.resourceBundle.getString("SGFParse.doubleEngineHintTitle"),
                      JOptionPane.OK_CANCEL_OPTION);
              if (ret == JOptionPane.OK_OPTION) {
                Lizzie.config.toggleExtraMode(2);
              }
            }
          }
        });

    return returnValue;
  }

  public static boolean loadFromString(String sgfString) {
    // Clear the board
    Lizzie.board.clear(false);
    isExtraMode2 = false;
    Lizzie.board.isLoadingFile = true;
    boolean result = parse(sgfString);
    if (Lizzie.config.loadSgfLast)
      while (Lizzie.board.nextMove(false))
        ;
    if (Lizzie.board.hasStartStone) {
      int lenth2 = Lizzie.board.startStonelist.size();
      for (int i = 0; i < lenth2; i++) {
        Movelist move = Lizzie.board.startStonelist.get(lenth2 - 1 - i);
        String color = move.isblack ? "b" : "w";
        if (!move.ispass) {
          Lizzie.leelaz.sendCommand(
              "play " + color + " " + Board.convertCoordinatesToName(move.x, move.y));
        }
      }
    }
    Lizzie.board.isLoadingFile = false;
    if (isExtraMode2 && !Lizzie.config.isDoubleEngineMode() && !Lizzie.config.isAutoAna) {
      int ret =
          JOptionPane.showConfirmDialog(
              Lizzie.frame,
              Lizzie.resourceBundle.getString("SGFParse.doubleEngineHint"),
              Lizzie.resourceBundle.getString("SGFParse.doubleEngineHintTitle"),
              JOptionPane.OK_CANCEL_OPTION);
      if (ret == JOptionPane.OK_OPTION) {
        Lizzie.config.toggleExtraMode(2);
      }
    }
    return result;
  }

  public static boolean loadFromStringforedit(String sgfString) {
    // Clear the board
    Lizzie.board.clearforedit();
    Lizzie.board.hasStartStone = false;
    Lizzie.board.startStonelist = new ArrayList<Movelist>();
    Lizzie.board.isLoadingFile = true;
    boolean result = parse(sgfString);
    Lizzie.board.isLoadingFile = false;
    return result;
  }

  public static String passPos() {
    return "";
    //    (Board.boardWidth <= 51 && Board.boardHeight <= 51)
    //        ? String.format(
    //            "%c%c", alphabet.charAt(Board.boardWidth), alphabet.charAt(Board.boardHeight))
    //        : "";
  }

  public static boolean isPassPos(String pos) {
    // TODO
    String passPos = passPos();
    return pos.isEmpty() || passPos.equals(pos);
  }

  public static int[] convertSgfPosToCoord(String pos) {
    if (pos.length() < 2) return null;
    if (isPassPos(pos)) return null;
    if (Board.boardHeight >= 52 || Board.boardWidth >= 52) {
      String[] params = pos.trim().split("_");
      if (params.length == 2) {
        try {
          int[] ret = new int[2];
          ret[0] = Integer.parseInt(params[0]);
          ret[1] = Integer.parseInt(params[1]);
          return ret;
        } catch (NumberFormatException e) {
          e.printStackTrace();
          return null;
        }
      } else {
        return null;
      }
    } else {
      int[] ret = new int[2];
      ret[0] = alphabet.indexOf(pos.charAt(0));
      ret[1] = alphabet.indexOf(pos.charAt(1));
      return ret;
    }
  }

  public static boolean isSGF(String value) {
    final Pattern SGF_PATTERN = Pattern.compile("(?s).*?(\\(\\s*;{0,1}.*\\))(?s).*?");
    Matcher sgfMatcher = SGF_PATTERN.matcher(value);
    if (!sgfMatcher.matches()) {
      value = "(;" + value.substring(1);
      Matcher sgfMatcher2 = SGF_PATTERN.matcher(value);
      if (!sgfMatcher2.matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean parse(String value) {
    // Drop anything outside "(;...)"
    boolean oriPlaySound = Lizzie.config.playSound;
    Lizzie.config.playSound = false;
    final Pattern SGF_PATTERN = Pattern.compile("(?s).*?(\\(\\s*;{0,1}.*\\))(?s).*?");
    Matcher sgfMatcher = SGF_PATTERN.matcher(value);
    if (sgfMatcher.matches()) {
      value = sgfMatcher.group(1);
    } else {
      value = "(;" + value.substring(1);
      Matcher sgfMatcher2 = SGF_PATTERN.matcher(value);
      if (sgfMatcher2.matches()) {
        value = sgfMatcher2.group(1);
      } else {
        return false;
      }
    }

    int[] boardSize = parseBoardSize(value);
    Lizzie.board.reopen(boardSize[0], boardSize[1]);

    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, BoardHistoryNode> subTreeStepMap = new HashMap<Integer, BoardHistoryNode>();
    // Comment of the game head
    String headComment = "";
    // Game properties
    Map<String, String> gameProperties = new HashMap<String, String>();
    Map<String, String> pendingProps = new HashMap<String, String>();
    PendingSetupMetadata pendingSetupMetadata = new PendingSetupMetadata();
    boolean inTag = false,
        isMultiGo = false,
        escaping = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    boolean inProp = false;
    int topLevelNodeCount = 0;
    boolean allowRootSetupOnCurrentNode = false;

    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();
    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    if (value.matches("(?s).*\\)\\s*\\)")) {
      isMultiGo = true;
    }

    String blackPlayer = "", whitePlayer = "";
    String result = "";
    // boolean isChineseRule = true;
    // boolean hasHandicap = false;
    Double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    // Support unicode characters (UTF-8)
    int len = value.length();
    boolean shouldProcessDummy = false;
    for (int i = 0; i < len; i++) {
      char c = value.charAt(i);
      if (escaping) {
        // Any char following "\" is inserted verbatim
        // (ref) "3.2. Text" in https://www.red-bean.com/sgf/sgf4.html
        tagContentBuilder.append(c == 'n' ? "\n" : c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(Lizzie.board.getHistory(), pendingSetupMetadata);
            subTreeDepth += 1;
            // Initialize the step count
            subTreeStepMap.put(subTreeDepth, Lizzie.board.getHistory().getCurrentHistoryNode());
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
            pendingProps = new HashMap<String, String>();
          } else {
            if (i > 0) {
              // Allow the comment tag includes '('
              tagContentBuilder.append(c);
            }
          }
          break;
        case ')':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(Lizzie.board.getHistory(), pendingSetupMetadata);
            if (isMultiGo) {
              // Restore to the variation node
              // int varStep = subTreeStepMap.get(subTreeDepth);
              BoardHistoryNode node = subTreeStepMap.get(subTreeDepth);
              //  for (int s = 0; s < varStep; s++)
              while (Lizzie.board.getHistory().getCurrentHistoryNode() != node) {
                if (!Lizzie.board.getHistory().previous().isPresent()) break;
              }
              //  System.out.println(subTreeDepth+" | "+varStep);
            }
            subTreeDepth -= 1;
          } else {
            // Allow the comment tag includes '('
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          if (!inProp) {
            inProp = true;
            if (subTreeDepth > 1 && !isMultiGo) {
              break;
            }
            inTag = true;
            String tagTemp = tagBuilder.toString();
            if (!tagTemp.isEmpty()) {
              // Ignore small letters in tags for the long format Smart-Go file.
              // (ex) "PlayerBlack" ==> "PB"
              // It is the default format of mgt, an old SGF tool.
              // (Mgt is still supported in Debian and Ubuntu.)
              tag = tagTemp.replaceAll("[a-z]", "");
            }
            tagContentBuilder = new StringBuilder();
          } else {
            tagContentBuilder.append(c);
          }
          break;
        case ']':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          inTag = false;
          inProp = false;
          tagBuilder = new StringBuilder();
          String tagContent = tagContentBuilder.toString();
          // We got tag, we can parse this tag now.
          if (tag.equals("DD")) {
            if (tagContent.equals("true")) {
              shouldProcessDummy = true;
            }
          } else if (tag.equals("B") || tag.equals("W")) {

            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent);
            // Save the step count
            //  subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            boolean newBranch =
                Lizzie.board
                    .getHistory()
                    .getCurrentHistoryNode()
                    .hasVariations(); // (subTreeStepMap.get(subTreeDepth) == 1);
            if (move == null) {
              if (shouldProcessDummy) {
                shouldProcessDummy = false;
                Stone colors =
                    Lizzie.board.getHistory().getLastMoveColor().isBlack()
                        ? Stone.WHITE
                        : Stone.BLACK;
                Lizzie.board.getHistory().pass(colors, false, false);
                Lizzie.board.getHistory().previous();
                Lizzie.board.getHistory().pass(color, newBranch, false);
                Lizzie.board
                        .getHistory()
                        .getCurrentHistoryNode()
                        .previous()
                        .get()
                        .next()
                        .get()
                        .getData()
                        .dummy =
                    true;
              } else Lizzie.board.getHistory().pass(color, newBranch, false);
            } else {
              if (shouldProcessDummy) {
                shouldProcessDummy = false;
                Stone colors =
                    Lizzie.board.getHistory().getLastMoveColor().isBlack()
                        ? Stone.WHITE
                        : Stone.BLACK;
                Lizzie.board.getHistory().pass(colors, false, false);
                Lizzie.board.getHistory().previous();
                Lizzie.board.getHistory().place(move[0], move[1], color, newBranch);
                Lizzie.board
                        .getHistory()
                        .getCurrentHistoryNode()
                        .previous()
                        .get()
                        .next()
                        .get()
                        .getData()
                        .dummy =
                    true;
              } else Lizzie.board.getHistory().place(move[0], move[1], color, newBranch);
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(Lizzie.board.getHistory(), pendingProps);
            }
          } else if (tag.equals("C")) {
            // Support comment
            boolean rootSetupContext =
                isRootSetupContext(
                    Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode);
            if (rootSetupContext) {
              headComment = tagContent;
            } else if (shouldBufferLeadingSetupMetadata(
                currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
              bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (isAnalysisTag(tag)) {
            if (isSecondaryEngineAnalysisTag(tag)) {
              isExtraMode2 = true;
            }
            boolean rootSetupContext =
                isRootSetupContext(
                    Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode);
            if (shouldBufferLeadingSetupAnalysis(
                currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext)) {
              bufferLeadingSetupAnalysis(pendingSetupMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              applyAnalysisTagOnDetachedHistory(target.getData(), tag, tagContent);
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent);
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(
                Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(Lizzie.board.getHistory(), tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    Lizzie.board.getHistory(), newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(
                  Lizzie.board.getHistory(), target, pendingSetupMetadata);
              addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(Lizzie.board.getHistory(), target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(
                Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(Lizzie.board.getHistory(), tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(
                    Lizzie.board.getHistory(), newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(
                      Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(
                  Lizzie.board.getHistory(), target, pendingSetupMetadata);
              applyCurrentPlayerPropertyOnNode(Lizzie.board.getHistory(), target, tagContent);
            }
          } else if (tag.equals("PB")) {
            blackPlayer = tagContent;
          } else if (tag.equals("PW")) {
            whitePlayer = tagContent;
          } else if (tag.equals("RE")) {
            result = tagContent;
          } else if (tag.equals("DZ")) {
            if (tagContent.equals("Y")) {
              Lizzie.board.isPkBoard = true;
            }
            if (tagContent.equals("KB")) {
              Lizzie.board.isPkBoard = true;
              Lizzie.board.isKataBoard = true;
              Lizzie.board.isPkBoardKataB = true;
            }
            if (tagContent.equals("KW")) {
              Lizzie.board.isPkBoard = true;
              Lizzie.board.isKataBoard = true;
              Lizzie.board.isPkBoardKataW = true;
            }
            if (tagContent.equals("G")) {
              Lizzie.board.isKataBoard = true;
            }
          } else if (tag.equals("KM")
              || tag.equals("KO")) { // Cyberoro and some site uses komi tag as KO.
            try {
              if (!tagContent.trim().isEmpty()) {
                komi = Double.parseDouble(tagContent);
              }
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          }
          //          else if (tag.equals("HA")) {
          //            try {
          //              if (!tagContent.trim().isEmpty()) {
          //                if (Integer.parseInt(tagContent.trim()) > 0) hasHandicap = true;
          //              }
          //            } catch (NumberFormatException e) {
          //              e.printStackTrace();
          //            }
          //          }
          //          else if (tag.equals("RU")) {
          //            if (!tagContent.trim().isEmpty()) {
          //              String rules = tagContent.toLowerCase();
          //              if (rules.contains("japanese")
          //                  || rules.contains("jp")
          //                  || rules.contains("korean")
          //                  || rules.contains("kr")) isChineseRule = false;
          //            }
          //          }
          else {
            if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch =
                      Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      Lizzie.board.getHistory(), newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(
                    Lizzie.board.getHistory(), target, pendingSetupMetadata);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(
                      Lizzie.board.getHistory(), target, move[0], move[1], Stone.EMPTY);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, false, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else if (!"FIT".equals(tag)) {
                boolean firstProp =
                    Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                // (subTreeStepMap.get(subTreeDepth).hasVariations());
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  addNodePropertyOnNode(
                      Lizzie.board.getHistory(),
                      Lizzie.board.getHistory().getCurrentHistoryNode(),
                      tag,
                      tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(
                      Lizzie.board.getHistory(), moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent);
                applyRootSetupProperty(
                    Lizzie.board.getHistory(), tag, tagContent, move, Stone.EMPTY);
              } else if (rootSetupContext) {
                if ("N".equals(tag) && headComment.isEmpty()) headComment = tagContent;
                else addProperty(gameProperties, tag, tagContent);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch =
                      Lizzie.board.getHistory().getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(
                      Lizzie.board.getHistory(), newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(
                    Lizzie.board.getHistory(), target, pendingSetupMetadata);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(
                      Lizzie.board.getHistory(), target, move[0], move[1], Stone.EMPTY);
                }
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(
                        Lizzie.board.getHistory(), currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(Lizzie.board.getHistory(), target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (inProp) {
            // support C[a;b;c;]
            tagContentBuilder.append(c);
          } else if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingSetupMetadataToCurrentNode(Lizzie.board.getHistory(), pendingSetupMetadata);
            topLevelNodeCount += 1;
            allowRootSetupOnCurrentNode = topLevelNodeCount == 1;
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C")) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
    flushPendingSetupMetadataToCurrentNode(Lizzie.board.getHistory(), pendingSetupMetadata);
    if (Lizzie.config.readKomi) {
      //      if (!hasHandicap && komi == 0) {
      //        if (isChineseRule) komi = 7.5;
      //        else komi = 6.5;
      //      }
      if (komi >= 200) {
        komi = komi / 100;
        if (komi <= 4 && komi >= -4) komi = komi * 2;
      }
      if (komi.toString().endsWith(".75") || komi.toString().endsWith(".25")) komi = komi * 2;
      if (Math.abs(komi) < Board.boardWidth * Board.boardHeight) {
        Lizzie.board.getHistory().getGameInfo().setKomi(komi);
        Lizzie.board.getHistory().getGameInfo().changeKomi();
        if (EngineManager.currentEngineNo >= 0) {
          Lizzie.leelaz.sendCommand("komi " + komi);
        }
      }
    }
    Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
    Lizzie.frame.setResult(result);
    GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
    gameInfo.setPlayerBlack(blackPlayer);
    gameInfo.setPlayerWhite(whitePlayer);
    gameInfo.setResult(result);
    // Rewind to game start
    while (Lizzie.board.previousMove(false))
      ;
    // Set AW/AB Comment
    if (!headComment.isEmpty()) {
      Lizzie.board.comment(headComment);
    }
    if (gameProperties.size() > 0) {
      Lizzie.board.addNodeProperties(gameProperties);
    }
    stabilizeRootSetupSideToPlay(Lizzie.board.getHistory());
    Lizzie.config.playSound = oriPlaySound;
    return true;
  }

  public static String saveToString(boolean forUpload) throws IOException {
    try (StringWriter writer = new StringWriter()) {
      saveToStream(Lizzie.board, writer, forUpload, false);
      return writer.toString();
    }
  }

  public static String saveMainTrunkRawToString() throws IOException {
    boolean originalSavingRaw = LizzieFrame.isSavingRaw;
    boolean originalSavingRawComment = LizzieFrame.isSavingRawComment;
    GameInfo originalGameInfo = copyGameInfo(Lizzie.board.getHistory().getGameInfo());
    int startMoveNumber = 0;
    boolean blackToPlay = Lizzie.board.getHistory().getStart().getData().blackToPlay;
    if (Lizzie.board.hasStartStone) {
      startMoveNumber += Lizzie.board.startStonelist.size();
    }
    try {
      Lizzie.board.saveListForEdit();
      Lizzie.board.clearforedit();
      Lizzie.board.getHistory().setGameInfo(originalGameInfo);
      Lizzie.board.setMoveListWithFlatten(
          Lizzie.board.tempallmovelist, startMoveNumber, blackToPlay);
      Lizzie.board.getHistory().getStart().getData().comment = "";
      LizzieFrame.isSavingRaw = true;
      LizzieFrame.isSavingRawComment = false;
      return saveToString(false);
    } finally {
      LizzieFrame.isSavingRaw = originalSavingRaw;
      LizzieFrame.isSavingRawComment = originalSavingRawComment;
      Lizzie.board.clearEditStuff();
    }
  }

  private static GameInfo copyGameInfo(GameInfo original) {
    GameInfo copy = new GameInfo();
    if (original == null) {
      return copy;
    }
    copy.setPlayerBlack(original.getPlayerBlack());
    copy.setPlayerWhite(original.getPlayerWhite());
    copy.setDate(original.getDate());
    copy.setKomiNoMenu(original.getKomi());
    copy.setHandicap(original.getHandicap());
    copy.setResult(original.getResult());
    return copy;
  }

  public static void appendGameTimeAndPlayouts() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    long blackPlayouts = 0;
    long whitePlayouts = 0;
    while (node.next().isPresent()) {
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.WHITE)) {
        blackPlayouts += node.getData().getPlayouts();
      }
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.BLACK)) {
        whitePlayouts += node.getData().getPlayouts();
      }
      node = node.next().get();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.WHITE)) {
      blackPlayouts += node.getData().getPlayouts();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.BLACK)) {
      whitePlayouts += node.getData().getPlayouts();
    }
    node = Lizzie.board.getHistory().getStart();
    if (Lizzie.config.chkEngineSgfStart)
      node.getData().comment =
          node.getData().comment
              + Lizzie.resourceBundle.getString("SGFParse.startGameSgf")
              + LizzieFrame.toolbar.currentEnginePkSgfNum
              + "\n";
    node.getData().comment +=
        Lizzie.resourceBundle.getString("SGFParse.blackTotalTime")
            + +Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex)
                    .pkMoveTimeGame
                / (float) 1000
            + Lizzie.resourceBundle.getString("SGFParse.seconds")
            + "\n"
            + Lizzie.resourceBundle.getString("SGFParse.totalVisits")
            + blackPlayouts;

    node.getData().comment +=
        "\n"
            + Lizzie.resourceBundle.getString("SGFParse.whiteTotalTime")
            + Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex)
                    .pkMoveTimeGame
                / (float) 1000
            + Lizzie.resourceBundle.getString("SGFParse.seconds")
            + "\n"
            + Lizzie.resourceBundle.getString("SGFParse.totalVisits")
            + +whitePlayouts;
    if (EngineManager.engineGameInfo.firstEngineIndex
        == EngineManager.engineGameInfo.blackEngineIndex) {
      EngineManager.engineGameInfo.firstEngineTotlePlayouts += blackPlayouts;
      EngineManager.engineGameInfo.secondEngineTotlePlayouts += whitePlayouts;

    } else {
      EngineManager.engineGameInfo.firstEngineTotlePlayouts += whitePlayouts;
      EngineManager.engineGameInfo.secondEngineTotlePlayouts += blackPlayouts;
    }
    EngineManager.engineGameInfo.firstEngineTotleTime +=
        Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.firstEngineIndex)
            .pkMoveTimeGame;
    EngineManager.engineGameInfo.secondEngineTotleTime +=
        Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.secondEngineIndex)
            .pkMoveTimeGame;
  }

  public static void appendAiScoreBlunder() {
    int analyzedBlack = 0;
    int analyzedWhite = 0;
    double blackValue = 0;
    double whiteValue = 0;
    List<BlunderMoves> blackDiffWinrate = new ArrayList<BlunderMoves>();
    List<BlunderMoves> whiteDiffWinrate = new ArrayList<BlunderMoves>();
    BoardHistoryNode node = Lizzie.board.getHistory().getEnd();
    while (node.previous().isPresent()) {
      node = node.previous().get();
      NodeInfo nodeInfo = node.nodeInfoMain;
      if (nodeInfo.analyzed) {
        if (nodeInfo.isBlack) {
          blackValue = blackValue + nodeInfo.percentsMatch;
          // + Math.pow(nodeInfo.percentsMatch, (double) 1 / Lizzie.config.matchAiTemperature);
          analyzedBlack = analyzedBlack + 1;
          blackDiffWinrate.add(new BlunderMoves(nodeInfo.moveNum, nodeInfo.diffWinrate));
        } else {
          whiteValue = whiteValue + nodeInfo.percentsMatch;
          // + Math.pow(nodeInfo.percentsMatch, (double) 1 / Lizzie.config.matchAiTemperature);
          analyzedWhite = analyzedWhite + 1;
          whiteDiffWinrate.add(new BlunderMoves(nodeInfo.moveNum, nodeInfo.diffWinrate));
        }
      }
    }

    BoardHistoryNode startNode = Lizzie.board.getHistory().getStart();
    if (analyzedBlack >= 10) {
      String bAiScore = String.format(Locale.ENGLISH, "%.1f", blackValue * 100 / analyzedBlack);
      String infoString =
          Lizzie.resourceBundle.getString("SGFParse.blackAiScore")
              + bAiScore
              + "\n"
              + Lizzie.resourceBundle.getString("SGFParse.blackTop10");
      Collections.sort(blackDiffWinrate);
      for (int i = 0; i < 10; i++) {
        if (i < 9) infoString = infoString + " " + blackDiffWinrate.get(i).toString() + ",";
        else infoString = infoString + " " + blackDiffWinrate.get(i).toString();
      }
      if (startNode.getData().comment.equals("")) startNode.getData().comment = infoString;
      else startNode.getData().comment += "\n" + infoString;
    }
    if (analyzedWhite >= 10) {
      String wAiScore = String.format(Locale.ENGLISH, "%.1f", whiteValue * 100 / analyzedWhite);
      String infoString =
          Lizzie.resourceBundle.getString("SGFParse.whiteAiScore")
              + wAiScore
              + "\n"
              + Lizzie.resourceBundle.getString("SGFParse.whiteTop10");
      Collections.sort(whiteDiffWinrate);
      for (int i = 0; i < 10; i++) {
        if (i < 9) infoString = infoString + " " + whiteDiffWinrate.get(i).toString() + ",";
        else infoString = infoString + " " + whiteDiffWinrate.get(i).toString();
      }
      if (startNode.getData().comment.equals("")) startNode.getData().comment = infoString;
      else startNode.getData().comment += "\n" + infoString;
    }
  }

  //
  //  public static void appendTimeAndPlayouts() {
  //    appendGameTime();
  //    appendGamePlayouts();
  //  }

  public static void save(Board board, String filename) throws IOException {
    save(board, filename, false);
  }

  public static void save(Board board, String filename, boolean isAutoSave) throws IOException {
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(filename), "utf-8")) {
      saveToStream(board, writer, false, isAutoSave);
    }
  }

  private static void saveToStream(
      Board board, Writer writer, boolean forUpload, boolean fromAutoSave) throws IOException {
    saveToStream(board, writer, forUpload, fromAutoSave, false, false);
  }

  private static void saveToStream(
      Board board,
      Writer writer,
      boolean forUpload,
      boolean fromAutoSave,
      boolean mainTrunkOnly,
      boolean stripRootMetadata)
      throws IOException {
    BoardHistoryList history = board.getHistory().shallowCopy();
    int[] historyBoardSize = resolveHistoryBoardSize(history);
    int historyBoardWidth = historyBoardSize[0];
    int historyBoardHeight = historyBoardSize[1];
    String boardSizeTag = formatBoardSizeTag(historyBoardWidth, historyBoardHeight);
    ParseBoardContext boardContext = captureParseBoardContext();
    boolean switchedBoardContext =
        boardContext.boardWidth != historyBoardWidth
            || boardContext.boardHeight != historyBoardHeight;
    try {
      if (switchedBoardContext) {
        applyParseBoardContext(historyBoardWidth, historyBoardHeight);
      }

      // collect game info
      GameInfo gameInfo = history.getGameInfo();
      String playerB = gameInfo.getPlayerBlack();
      String playerW = gameInfo.getPlayerWhite();
      String result = gameInfo.getResult();
      Double komi = gameInfo.getKomi();
      Integer handicap = gameInfo.getHandicap();
      String date = SGF_DATE_FORMAT.format(gameInfo.getDate());

      // add SGF header
      StringBuilder builder = new StringBuilder("(;");
      StringBuilder generalProps = new StringBuilder("");
      if (handicap != 0) generalProps.append(String.format(Locale.ENGLISH, "HA[%s]", handicap));
      if (LizzieFrame.isSavingRaw) {
        generalProps.append(
            String.format(
                "KM[%s]PW[%s]PB[%s]DT[%s]RE[%s]SZ[%s]CA[UTF-8]",
                komi, playerW, playerB, date, result, boardSizeTag));
      } else {
        if (EngineManager.isEngineGame || EngineManager.isSaveingEngineSGF) {
          Lizzie.board.updateWinrate();
          SGFParser.appendTime();
          if (Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex)
              .isKatago) {
            String rules = "";
            boolean usingSpecificRues = false;
            switch (Lizzie.engineManager.engineList.get(
                    EngineManager.engineGameInfo.blackEngineIndex)
                .usingSpecificRules) {
              case 1:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chinese");
                usingSpecificRues = true;
                break;
              case 2:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chn-ancient");
                usingSpecificRues = true;
                break;
              case 3:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.japanese");
                usingSpecificRues = true;
                break;
              case 4:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.tromp-taylor");
                usingSpecificRues = true;
                break;
              case 5:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.others");
                usingSpecificRues = true;
                break;
            }

            if (usingSpecificRues) {
              if (Lizzie.board.getHistory().getStart().getData().comment.equals(""))
                Lizzie.board.getHistory().getStart().getData().comment +=
                    Lizzie.resourceBundle.getString("SGFParse.blackRules") + rules;
              else
                Lizzie.board.getHistory().getStart().getData().comment +=
                    "\n" + Lizzie.resourceBundle.getString("SGFParse.blackRules") + rules;
            }
          }
          if (Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex)
              .isKatago) {
            String rules = "";
            boolean usingSpecificRues = false;
            switch (Lizzie.engineManager.engineList.get(
                    EngineManager.engineGameInfo.whiteEngineIndex)
                .usingSpecificRules) {
              case 1:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chinese");
                usingSpecificRues = true;
                break;
              case 2:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chn-ancient");
                usingSpecificRues = true;
                break;
              case 3:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.japanese");
                usingSpecificRues = true;
                break;
              case 4:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.tromp-taylor");
                usingSpecificRues = true;
                break;
              case 5:
                rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.others");
                usingSpecificRues = true;
                break;
            }

            if (usingSpecificRues) {
              if (Lizzie.board.getHistory().getStart().getData().comment.equals(""))
                Lizzie.board.getHistory().getStart().getData().comment +=
                    Lizzie.resourceBundle.getString("SGFParse.whiteRules") + rules;
              else
                Lizzie.board.getHistory().getStart().getData().comment +=
                    "\n" + Lizzie.resourceBundle.getString("SGFParse.whiteRules") + rules;
            }
          }
        } else {
          if (Lizzie.leelaz.isKatago && !fromAutoSave) {
            String rules = "";
            boolean usingSpecificRues = false;
            if (Lizzie.leelaz.isKatago) {
              switch (Lizzie.leelaz.usingSpecificRules) {
                case 1:
                  rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chinese");
                  usingSpecificRues = true;
                  break;
                case 2:
                  rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.chn-ancient");
                  usingSpecificRues = true;
                  break;
                case 3:
                  rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.japanese");
                  usingSpecificRues = true;
                  break;
                case 4:
                  rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.tromp-taylor");
                  usingSpecificRues = true;
                  break;
                case 5:
                  rules = Lizzie.resourceBundle.getString("LizzieFrame.currentRules.others");
                  usingSpecificRues = true;
                  break;
              }
            }
            if (usingSpecificRues) {
              if (Lizzie.board.getHistory().getStart().getData().comment.equals(""))
                Lizzie.board.getHistory().getStart().getData().comment +=
                    Lizzie.resourceBundle.getString("SGFParse.rules") + rules;
              else if (!Lizzie.board
                  .getHistory()
                  .getStart()
                  .getData()
                  .comment
                  .contains(Lizzie.resourceBundle.getString("SGFParse.rules")))
                Lizzie.board.getHistory().getStart().getData().comment =
                    Lizzie.resourceBundle.getString("SGFParse.rules")
                        + rules
                        + "\n\n"
                        + Lizzie.board.getHistory().getStart().getData().comment;
              else {
                String oldComment = Lizzie.board.getHistory().getStart().getData().comment;
                int leftIndex =
                    oldComment.indexOf(
                        "\n",
                        oldComment.indexOf(Lizzie.resourceBundle.getString("SGFParse.rules")));
                Lizzie.board.getHistory().getStart().getData().comment =
                    oldComment.substring(
                            0,
                            oldComment.indexOf(Lizzie.resourceBundle.getString("SGFParse.rules")))
                        + Lizzie.resourceBundle.getString("SGFParse.rules")
                        + rules
                        + (leftIndex > 0 ? oldComment.substring(leftIndex) : "");
              }
            }
          }
        }

        if (EngineManager.isEngineGame || Lizzie.board.isPkBoard) {
          if (Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex)
                  .isKatago
              || Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex)
                  .isSai
              || Lizzie.board.isPkBoardKataW)
            generalProps.append(
                String.format(
                    "KM[%s]PW[%s]PB[%s]DT[%s]DZ[KW]AP[LizzieYzy Next: %s]RE[%s]SZ[%s]CA[UTF-8]",
                    komi, playerW, playerB, date, Lizzie.nextVersion, result, boardSizeTag));
          else if (Lizzie.engineManager.engineList.get(
                      EngineManager.engineGameInfo.blackEngineIndex)
                  .isKatago
              || Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex)
                  .isSai
              || Lizzie.board.isPkBoardKataB)
            generalProps.append(
                String.format(
                    "KM[%s]PW[%s]PB[%s]DT[%s]DZ[KB]AP[LizzieYzy Next: %s]RE[%s]SZ[%s]CA[UTF-8]",
                    komi, playerW, playerB, date, Lizzie.nextVersion, result, boardSizeTag));
          else
            generalProps.append(
                String.format(
                    "KM[%s]PW[%s]PB[%s]DT[%s]DZ[Y]AP[LizzieYzy Next: %s]RE[%s]SZ[%s]CA[UTF-8]",
                    komi, playerW, playerB, date, Lizzie.nextVersion, result, boardSizeTag));
        } else {
          if (Lizzie.leelaz.isKatago || Lizzie.board.isKataBoard)
            generalProps.append(
                String.format(
                    "KM[%s]PW[%s]PB[%s]DT[%s]DZ[G]AP[LizzieYzy Next: %s]RE[%s]SZ[%s]CA[UTF-8]",
                    komi, playerW, playerB, date, Lizzie.nextVersion, result, boardSizeTag));
          else
            generalProps.append(
                String.format(
                    "KM[%s]PW[%s]PB[%s]DT[%s]AP[LizzieYzy Next: %s]RE[%s]SZ[%s]CA[UTF-8]",
                    komi, playerW, playerB, date, Lizzie.nextVersion, result, boardSizeTag));
        }
      }
      // To append the winrate to the comment of sgf we might need to update the
      // Winrate
      // if (Lizzie.config.appendWinrateToComment) {
      // Lizzie.board.updateWinrate();
      // }

      // move to the first move
      history.toStart();

      // Game properties
      BoardData rootData = history.getData();
      rootData.addProperties(generalProps.toString());
      if (rootData.isSnapshotNode()) {
        builder.append(materializedRootSnapshotProperties(rootData, history.getStones()));
      } else {
        builder.append(rootData.propertiesString());
        if (handicap != 0) {
          builder.append("AB");
          Stone[] stones = history.getStones();
          for (int i = 0; i < stones.length; i++) {
            Stone stone = stones[i];
            if (stone.isBlack()) {
              // i = x * Board.BOARD_SIZE + y;
              builder.append(String.format(Locale.ENGLISH, "[%s]", asCoord(i)));
            }
          }
        } else {
          // Process the AW/AB stone
          Stone[] stones = history.getStones();
          StringBuilder abStone = new StringBuilder();
          StringBuilder awStone = new StringBuilder();
          for (int i = 0; i < stones.length; i++) {
            Stone stone = stones[i];
            if (stone.isBlack() || stone.isWhite()) {
              if (stone.isBlack()) {
                abStone.append(String.format(Locale.ENGLISH, "[%s]", asCoord(i)));
              } else {
                awStone.append(String.format(Locale.ENGLISH, "[%s]", asCoord(i)));
              }
            }
          }
          if (abStone.length() > 0) {
            builder.append("AB").append(abStone);
          }
          if (awStone.length() > 0) {
            builder.append("AW").append(awStone);
          }
        }
      }

      // The AW/AB Comment
      if (!stripRootMetadata && !history.getData().comment.isEmpty()) {
        String coment = history.getData().comment;
        if (forUpload) {
          coment =
              removeWinrateComment(
                  history.getData().isKataData, history.getData().isSaiData, coment);
        }
        builder.append(String.format(Locale.ENGLISH, "C[%s]", Escaping(coment)));
      }
      BoardHistoryNode curNode = history.getCurrentHistoryNode();
      if (!stripRootMetadata) {
        try {
          if (hasPrimaryAnalysisPayload(curNode.getData()))
            builder.append(String.format(Locale.ENGLISH, "LZOP[%s]", formatNodeData(curNode)));
          if (hasSecondaryAnalysisPayload(curNode.getData()))
            builder.append(String.format(Locale.ENGLISH, "LZOP2[%s]", formatNodeData2(curNode)));
          if (!EngineManager.isEngineGame && !Lizzie.board.isPkBoard) {
            BoardData data = curNode.getData();
            if (Lizzie.board.isGameBoard) {
              if (data.getPlayouts() > 0 && curNode.next().isPresent())
                curNode.next().get().getData().comment = formatCommentForGame(curNode);
              else if (curNode.next().isPresent()) curNode.next().get().getData().comment = "";
            }
          }
        } catch (Exception e) {
          Lizzie.board.isLoadingFile = false;
        }
      }
      // replay moves, and convert them to tags.
      // * format: ";B[xy]" or ";W[xy]"
      // * with 'xy' = coordinates ; or 'tt' for pass.

      // Write variation tree
      BoardHistoryNode makerBeg = new BoardHistoryNode(null);
      BoardHistoryNode makerEnd = new BoardHistoryNode(null);
      BoardHistoryNode rootNode = history.getStart();
      Stack<BoardHistoryNode> stack = new Stack<>();
      stack.push(curNode);
      while (!stack.isEmpty()) {
        BoardHistoryNode cur = stack.pop();
        if (cur == makerBeg) {
          builder.append("(");
          continue;
        }
        if (cur == makerEnd) {
          builder.append(")");
          continue;
        }
        if (cur != rootNode) {
          builder = generateNode(board, cur, forUpload, builder);
        }
        if (mainTrunkOnly) {
          if (cur.next().isPresent()) {
            stack.push(cur.next().get());
          }
        } else {
          boolean hasBrothers = (cur.numberOfChildren() > 1);
          if (cur.numberOfChildren() >= 1) {
            for (int i = cur.numberOfChildren() - 1; i >= 0; i--) {
              if (hasBrothers) stack.push(makerEnd);
              stack.push(cur.getVariations().get(i));
              if (hasBrothers) stack.push(makerBeg);
            }
          }
        }
      }
      // close file
      builder.append(')');
      writer.append(builder.toString());
    } finally {
      if (switchedBoardContext) {
        restoreParseBoardContext(boardContext);
      }
    }
  }

  /** Generate node with variations */
  public static void appendComment() {
    // if (!Lizzie.config.showComment) return;
    // if (!Lizzie.leelaz.isLoaded()) return;
    if (EngineManager.isEngineGame || EngineManager.isSaveingEngineSGF) {
      if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts() > 0) {
        Lizzie.board.getHistory().getData().comment =
            formatCommentPk(Lizzie.board.getHistory().getCurrentHistoryNode());
      }
    } else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts() > 0) {
      if (Lizzie.board.isGameBoard) {
        Lizzie.board.getHistory().getData().comment =
            formatCommentForGame(Lizzie.board.getHistory().getCurrentHistoryNode());
      } else {
        Lizzie.board.getHistory().getData().comment =
            formatComment(Lizzie.board.getHistory().getCurrentHistoryNode());
      }
    }
  }

  public static void appendTime() {
    BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (node.getData().moveNumber >= 3 && node.getData().getPlayouts() > 0) {
      if (node.getData().blackToPlay)
        node.getData().comment +=
            "\n"
                + Lizzie.resourceBundle.getString("SGFParse.moveTime")
                + Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex)
                        .pkMoveTime
                    / 1000f
                + Lizzie.resourceBundle.getString("SGFParse.seconds");
      else
        node.getData().comment +=
            "\n"
                + Lizzie.resourceBundle.getString("SGFParse.moveTime")
                + Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex)
                        .pkMoveTime
                    / 1000f
                + Lizzie.resourceBundle.getString("SGFParse.seconds");
    }
  }

  //
  //  public static void appendCommentForPk() {
  //	  appendComment();
  //  }

  private static double getMatchValue(BoardHistoryNode node) {
    if (!node.isMainTrunk()) return 0;
    boolean isBlack = node.getData().blackToPlay;
    double matchValue = 0;
    int analyzed = 0;
    while (node.previous().isPresent()) {
      node = node.previous().get();
      NodeInfo nodeInfo = node.nodeInfoMain;
      if (nodeInfo.analyzed) {
        if (node.getData().blackToPlay && isBlack) {
          matchValue = matchValue + nodeInfo.percentsMatch;
          analyzed++;
        } else if (!node.getData().blackToPlay && !isBlack) {
          matchValue = matchValue + nodeInfo.percentsMatch;
          analyzed++;
        }
      }
    }
    if (analyzed == 0) return 0;
    return matchValue * 100 / analyzed;
  }

  private static StringBuilder generateNode(
      Board board, BoardHistoryNode node, boolean forUpload, StringBuilder builder)
      throws IOException {
    int[] nodeBoardSize = resolveNodeBoardSize(node);
    ParseBoardContext boardContext = captureParseBoardContext();
    boolean switchedBoardContext =
        boardContext.boardWidth != nodeBoardSize[0] || boardContext.boardHeight != nodeBoardSize[1];
    try {
      if (switchedBoardContext) {
        applyParseBoardContext(nodeBoardSize[0], nodeBoardSize[1]);
      }
      return generateNodeWithBoardContext(board, node, forUpload, builder);
    } finally {
      if (switchedBoardContext) {
        restoreParseBoardContext(boardContext);
      }
    }
  }

  private static StringBuilder generateNodeWithBoardContext(
      Board board, BoardHistoryNode node, boolean forUpload, StringBuilder builder)
      throws IOException {
    if (node == null) {
      return builder;
    }
    BoardData data = node.getData();
    if (data.isSnapshotNode() && node.previous().isPresent()) {
      builder.append(";");
      builder.append(materializedSetupProperties(node));
      appendSerializedComment(builder, node, forUpload, true);
      return builder;
    }
    if (shouldSerializeSetupSnapshot(data)) {
      builder.append(";");
      builder.append(data.propertiesString());
      appendSerializedComment(builder, node, forUpload, true);
      return builder;
    }
    if (!data.isHistoryActionNode()) {
      return builder;
    }
    String stone = "";
    if (Stone.BLACK.equals(data.lastMoveColor) || Stone.WHITE.equals(data.lastMoveColor)) {

      if (Stone.BLACK.equals(data.lastMoveColor)) stone = "B";
      else if (Stone.WHITE.equals(data.lastMoveColor)) stone = "W";

      builder.append(";");

      if (!data.dummy) {
        String sgfMove = data.isPassNode() ? passPos() : asCoord(data.lastMove.get());
        builder.append(String.format("%s[%s]", stone, sgfMove));
      }

      // Node properties
      builder.append(data.propertiesString());

      if (forUpload) {
        builder.append(String.format(Locale.ENGLISH, "FIT[%s]", getMatchValue(node)));
      }

      appendSerializedComment(builder, node, forUpload, true);
    }
    if (node.numberOfChildren() < 1)
      if (node.isEndDummay()) {
        builder.append(";DD[true]");
      }
    return builder;
  }

  private static boolean shouldSerializeSetupSnapshot(BoardData data) {
    if (!data.isSnapshotNode()) {
      return false;
    }
    Map<String, String> properties = data.getProperties();
    return properties.containsKey("AB")
        || properties.containsKey("AW")
        || properties.containsKey("AE");
  }

  private static String materializedRootSnapshotProperties(BoardData rootData, Stone[] stones) {
    Map<String, String> properties = new LinkedHashMap<String, String>();
    Map<String, String> rootSetup = collectCurrentRootSetup(stones, rootData.blackToPlay);
    boolean setupInserted = false;
    for (Map.Entry<String, String> entry : rootData.getProperties().entrySet()) {
      String key = entry.getKey();
      if (isRootSetupSerializationProperty(key)) {
        if (!setupInserted) {
          addProperties(properties, rootSetup);
          setupInserted = true;
        }
        continue;
      }
      addProperty(properties, key, entry.getValue());
    }
    if (!setupInserted) {
      addProperties(properties, rootSetup);
    }
    return propertiesString(properties);
  }

  private static Map<String, String> collectCurrentRootSetup(Stone[] stones, boolean blackToPlay) {
    Map<String, String> setup = new LinkedHashMap<String, String>();
    for (int index = 0; index < stones.length; index++) {
      Stone stone = stones[index];
      if (stone.isBlack()) {
        addSetupProperty(setup, "AB", asCoord(index));
      } else if (stone.isWhite()) {
        addSetupProperty(setup, "AW", asCoord(index));
      }
    }
    addProperty(setup, "PL", blackToPlay ? "B" : "W");
    return setup;
  }

  private static boolean isRootSetupSerializationProperty(String key) {
    return "AB".equals(key) || "AW".equals(key) || "AE".equals(key) || "PL".equals(key);
  }

  private static String materializedSetupProperties(BoardHistoryNode node) {
    Map<String, String> properties = new LinkedHashMap<String, String>();
    BoardData data = node.getData();
    addProperty(properties, "PL", data.blackToPlay ? "B" : "W");
    appendSetupStoneDelta(properties, node);
    data.getProperties()
        .forEach(
            (key, value) -> {
              if ("PL".equals(key)) {
                return;
              }
              if (isSetupStoneProperty(key)) {
                addSemanticallyMatchingSetupValues(properties, node, key, value);
                return;
              }
              addProperty(properties, key, value);
            });
    return propertiesString(properties);
  }

  private static void appendSetupStoneDelta(Map<String, String> properties, BoardHistoryNode node) {
    Stone[] currentStones = node.getData().stones;
    Stone[] previousStones = node.previous().get().getData().stones;
    for (int index = 0; index < currentStones.length; index++) {
      Stone currentStone = currentStones[index];
      Stone previousStone = previousStones[index];
      if (currentStone == previousStone) {
        continue;
      }
      if (!previousStone.isEmpty()) {
        addSetupProperty(properties, "AE", asCoord(index));
      }
      if (currentStone.isBlack()) {
        addSetupProperty(properties, "AB", asCoord(index));
      } else if (currentStone.isWhite()) {
        addSetupProperty(properties, "AW", asCoord(index));
      }
    }
  }

  private static void addSemanticallyMatchingSetupValues(
      Map<String, String> properties, BoardHistoryNode node, String key, String rawValues) {
    if (rawValues == null || rawValues.isEmpty()) {
      return;
    }
    Stone[] currentStones = node.getData().stones;
    Stone[] previousStones = node.previous().get().getData().stones;
    boolean keepExplicitRemovedStoneMetadata = node.hasRemovedStone();
    String[] values = rawValues.split(",");
    for (String value : values) {
      String normalizedValue = value.trim();
      int[] coord = convertSgfPosToCoord(normalizedValue);
      if (!matchesMaterializedSetupSemantics(
          key, coord, currentStones, previousStones, keepExplicitRemovedStoneMetadata)) {
        continue;
      }
      addSetupProperty(properties, key, normalizedValue);
    }
  }

  private static boolean matchesMaterializedSetupSemantics(
      String key,
      int[] coord,
      Stone[] currentStones,
      Stone[] previousStones,
      boolean keepExplicitRemovedStoneMetadata) {
    if (coord == null || !isOnBoard(coord)) {
      return false;
    }
    int index = Board.getIndex(coord[0], coord[1]);
    Stone currentStone = currentStones[index];
    Stone previousStone = previousStones[index];
    if ("AB".equals(key)) {
      return currentStone != previousStone && currentStone.isBlack();
    }
    if ("AW".equals(key)) {
      return currentStone != previousStone && currentStone.isWhite();
    }
    if (!"AE".equals(key) || !currentStone.isEmpty()) {
      return false;
    }
    if (currentStone != previousStone) {
      return true;
    }
    return keepExplicitRemovedStoneMetadata;
  }

  private static boolean isOnBoard(int[] coord) {
    return coord[0] >= 0
        && coord[0] < Board.boardWidth
        && coord[1] >= 0
        && coord[1] < Board.boardHeight;
  }

  private static void addSetupProperty(Map<String, String> properties, String key, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    String existing = properties.get(key);
    if (existing == null || existing.isEmpty()) {
      properties.put(key, value);
      return;
    }
    if (containsSetupValue(existing, value)) {
      return;
    }
    properties.put(key, existing + "," + value);
  }

  private static boolean containsSetupValue(String existing, String value) {
    String[] entries = existing.split(",");
    for (String entry : entries) {
      if (entry.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSetupStoneProperty(String key) {
    return "AB".equals(key) || "AW".equals(key) || "AE".equals(key);
  }

  private static void appendSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload, boolean includeAnalysisData)
      throws IOException {
    if (!LizzieFrame.isSavingRaw) {
      appendStandardSerializedComment(builder, node, forUpload, includeAnalysisData);
      return;
    }
    if (LizzieFrame.isSavingRawComment) {
      appendRawSerializedComment(builder, node, forUpload);
    }
  }

  private static void appendStandardSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload, boolean includeAnalysisData)
      throws IOException {
    prepareCommentForSave(node);
    String curComment = getSerializedComment(node);
    if (shouldFormatSnapshotCommentForSave(node)) {
      curComment = formatSnapshotCommentForExport(node);
    }
    if (forUpload) {
      curComment =
          removeWinrateComment(node.getData().isKataData, node.getData().isSaiData, curComment);
    }
    if (!curComment.isEmpty()) {
      builder.append(String.format(Locale.ENGLISH, "C[%s]", Escaping(curComment)));
    }
    if (includeAnalysisData) {
      appendSerializedAnalysis(builder, node);
    }
  }

  private static void prepareCommentForSave(BoardHistoryNode node) throws IOException {
    BoardData data = node.getData();
    if (Lizzie.config.appendWinrateToComment) {
      if (!EngineManager.isEngineGame && !Lizzie.board.isPkBoard) {
        if (Lizzie.board.isGameBoard) {
          updateGameComment(node, data);
        } else {
          if (data.isSnapshotNode()) {
            return;
          }
          if (data.getPlayouts() > 0) data.comment = formatComment(node);
          if (Lizzie.config.isDoubleEngineMode() && data.getPlayouts2() > 0) {
            data.comment = formatComment2(node);
          }
        }
      }
      return;
    }
    if (!EngineManager.isEngineGame && !Lizzie.board.isPkBoard && Lizzie.board.isGameBoard) {
      updateGameComment(node, data);
    }
  }

  private static void updateGameComment(BoardHistoryNode node, BoardData data) throws IOException {
    if (data.getPlayouts() > 0 && node.next().isPresent()) {
      node.next().get().getData().comment = formatCommentForGame(node);
    } else if (node.next().isPresent()) {
      node.next().get().getData().comment = "";
    }
  }

  private static String getSerializedComment(BoardHistoryNode node) throws IOException {
    if (!EngineManager.isSaveingEngineSGF || !node.previous().isPresent()) {
      return node.getData().comment;
    }
    if (node.getData().isSnapshotNode()) {
      return node.getData().comment;
    }
    if (!node.previous().get().previous().isPresent()) {
      return formatCommentPk(node.previous().get());
    }
    return node.previous().get().getData().comment;
  }

  private static boolean shouldFormatSnapshotCommentForSave(BoardHistoryNode node) {
    return Lizzie.config.appendWinrateToComment
        && node.getData().isSnapshotNode()
        && !EngineManager.isEngineGame
        && !Lizzie.board.isPkBoard
        && !Lizzie.board.isGameBoard;
  }

  private static String formatSnapshotCommentForExport(BoardHistoryNode node) throws IOException {
    BoardData data = node.getData();
    String curComment = data.comment;
    if (data.getPlayouts() > 0) {
      curComment = formatComment(node);
    }
    if (Lizzie.config.isDoubleEngineMode() && data.getPlayouts2() > 0) {
      curComment = formatComment2(node);
    }
    return curComment;
  }

  private static void appendRawSerializedComment(
      StringBuilder builder, BoardHistoryNode node, boolean forUpload) throws IOException {
    BoardData data = node.getData();
    String curComment = data.comment;
    if (!EngineManager.isEngineGame && !Lizzie.board.isPkBoard) {
      if (data.isSnapshotNode()) {
        curComment = formatSnapshotCommentForExport(node);
      } else {
        if (data.getPlayouts() > 0) data.comment = formatComment(node);
        if (Lizzie.config.isDoubleEngineMode() && data.getPlayouts2() > 0) {
          data.comment = formatComment2(node);
        }
        curComment = data.comment;
      }
    }
    if (forUpload) {
      curComment =
          removeWinrateComment(node.getData().isKataData, node.getData().isSaiData, curComment);
    }
    if (!curComment.isEmpty()) {
      builder.append(String.format(Locale.ENGLISH, "C[%s]", Escaping(curComment)));
    }
  }

  private static void appendSerializedAnalysis(StringBuilder builder, BoardHistoryNode node)
      throws IOException {
    try {
      if (hasPrimaryAnalysisPayload(node.getData())) {
        builder.append(String.format(Locale.ENGLISH, "LZ[%s]", formatNodeData(node)));
      }
      if (hasSecondaryAnalysisPayload(node.getData())) {
        builder.append(String.format(Locale.ENGLISH, "LZ2[%s]", formatNodeData2(node)));
      }
    } catch (Exception e) {
      Lizzie.board.isLoadingFile = false;
    }
  }

  private static String removeWinrateComment(
      boolean isKataData, boolean isSaiData, String curComment) {
    String wp = "";
    if (!isKataData) {
      wp =
          "("
              + Lizzie.resourceBundle.getString("SGFParse.black")
              + " |"
              + Lizzie.resourceBundle.getString("SGFParse.white")
              + " )"
              + Lizzie.resourceBundle.getString("SGFParse.winrate")
              + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n\\("
              + ".*"
              + " / [0-9\\.]*[kmKM]* "
              + Lizzie.resourceBundle.getString("SGFParse.playouts")
              + "\\)\\n"
              + Lizzie.resourceBundle.getString("SGFParse.komi")
              + ".*";
    } else {
      if (isSaiData)
        wp =
            "("
                + Lizzie.resourceBundle.getString("SGFParse.black")
                + " |"
                + Lizzie.resourceBundle.getString("SGFParse.white")
                + " )"
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)*\n\\("
                + ".*"
                + " / [0-9\\.]*[kmKM]* "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + "\\)\\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + ".*";
      else
        wp =
            "("
                + Lizzie.resourceBundle.getString("SGFParse.black")
                + " |"
                + Lizzie.resourceBundle.getString("SGFParse.white")
                + " )"
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)* "
                + Lizzie.resourceBundle.getString("SGFParse.stdev")
                + " [0-9\\.\\-+]*\n\\("
                + ".*"
                + " / [0-9\\.]*[kmKM]* "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + "\\)\\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + ".*";
    }
    if (curComment.matches("(?s).*" + wp + "(?s).*")) {
      return curComment.replaceAll(wp, "");
    }
    return curComment;
  }

  /**
   * Format Comment with following format: Move <Move number> <Winrate> (<Last Move Rate
   * Difference>) (<Weight name> / <Playouts>)
   */
  public static String formatComment(BoardHistoryNode node) {
    //    if (node.getData().commented) return node.getData().comment;
    //    node.getData().commented = true;
    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? lastNode.get().getWinrate() : 50;
    // Current winrate
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    if (Lizzie.config.winrateAlwaysBlack) {
      curWR = validWinrate ? data.getWinrate() : lastWR;
    } else {
      curWR = validWinrate ? data.getWinrate() : 100 - lastWR;
    }

    // Last move difference winrate
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //      if (Lizzie.config.handicapInsteadOfWinrate) {
      //        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //      } else {
      double diff;
      if (Lizzie.config.winrateAlwaysBlack) {
        diff = lastWR - curWR;
      } else {
        diff = 100 - lastWR - curWR;
      }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
      // }
    }
    // if (Lizzie.engineManager.isEngineGame && node.moveNumberOfNode() <= 3) {
    // lastMoveDiff = "";
    // }
    //    String wf = "%s棋 胜率: %s %s\n(%s / %s 计算量)";
    boolean blackWinrate = !data.blackToPlay || Lizzie.config.winrateAlwaysBlack;
    String nc = "";
    //        String.format(
    //            wf,
    //           blackWinrate ? "黑" : "白",
    //            String.format(Locale.ENGLISH,"%.1f%%", 100 - curWR),
    //            lastMoveDiff,
    //            engine,
    //            playouts);

    if (data.isKataData) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = -data.scoreMean - node.previous().get().getData().scoreMean;
          if (Lizzie.config.winrateAlwaysBlack && Lizzie.board.getHistory().isBlacksTurn())
            diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = -node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
          if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        } else if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      } else {
        double score = -node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
          if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        } else if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s "
                + Lizzie.resourceBundle.getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;

        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                data.pda != 0
                    ? String.format(Locale.ENGLISH, "%.1f", data.getKomi())
                        + " "
                        + Lizzie.resourceBundle.getString("SGFParse.pda")
                        + data.pda
                    : String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      }
    } else {
      String wf =
          "%s "
              + Lizzie.resourceBundle.getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + Lizzie.resourceBundle.getString("SGFParse.playouts")
              + ")\n"
              + Lizzie.resourceBundle.getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? Lizzie.resourceBundle.getString("SGFParse.black")
                  : Lizzie.resourceBundle.getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
    }

    if (!data.comment.isEmpty()) {
      // [^\\(\\)/]*
      String wp = "";
      if (!data.isKataData) {
        wp =
            "("
                + Lizzie.resourceBundle.getString("SGFParse.black")
                + " |"
                + Lizzie.resourceBundle.getString("SGFParse.white")
                + " )"
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n\\("
                + ".*"
                + " / [0-9\\.]*[kmKM]* "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + "\\)\\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + ".*";
      } else {
        if (data.isSaiData)
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
        else
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)* "
                  + Lizzie.resourceBundle.getString("SGFParse.stdev")
                  + " [0-9\\.\\-+]*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
      }
      // if (Lizzie.leelaz.isKatago) wp = wp + "\n.*";
      if (data.comment.matches("(?s).*" + wp + "(?s).*")) {
        nc = data.comment.replaceAll(wp, nc);
      } else {
        nc = String.format(Locale.ENGLISH, "%s\n\n%s", data.comment, nc);
      }
    }
    return nc;
  }

  private static String formatCommentForGame(BoardHistoryNode node) {
    //    if (node.getData().commented) return node.getData().comment;
    //    node.getData().commented = true;

    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;
    // Current winrate
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    //  if (Lizzie.config.winrateAlwaysBlack) {
    curWR = validWinrate ? data.winrate : lastWR;
    //  } else {
    //    curWR = validWinrate ? data.getWinrate() : 100 - lastWR;
    //  }

    // Last move difference winrate
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //      if (Lizzie.config.handicapInsteadOfWinrate) {
      //        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //      } else {
      double diff;
      //  if (Lizzie.config.winrateAlwaysBlack) {
      //      diff = lastWR - curWR;
      //   } else {
      diff = 100 - lastWR - curWR;
      //   }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff < 0 ? "+" : "-", Math.abs(diff));
      // }
    }
    // if (Lizzie.engineManager.isEngineGame && node.moveNumberOfNode() <= 3) {
    // lastMoveDiff = "";
    // }
    //    String wf = "%s棋 胜率: %s %s\n(%s / %s 计算量)";
    boolean blackWinrate = !data.blackToPlay;
    String nc = "";
    //        String.format(
    //            wf,
    //           blackWinrate ? "黑" : "白",
    //            String.format(Locale.ENGLISH,"%.1f%%", 100 - curWR),
    //            lastMoveDiff,
    //            engine,
    //            playouts);

    if (data.isKataData) {
      String diffScore = "";
      // if (validLastWinrate && validWinrate) {
      if (node.previous().isPresent()) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = -data.scoreMean - node.previous().get().getData().scoreMean;
          if (Lizzie.config.winrateAlwaysBlack && Lizzie.board.getHistory().isBlacksTurn())
            diff = -diff;
          // if (!blackWinrate) diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
        //  }
        else if (node.previous().get().previous().isPresent()
            && node.previous().get().previous().get().getData().getPlayouts() > 0) {
          double diff =
              -data.scoreMean + node.previous().get().previous().get().getData().scoreMean;
          if (!blackWinrate) diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = data.scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.white")
                    : Lizzie.resourceBundle.getString("SGFParse.black"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      } else {
        double score = node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s "
                + Lizzie.resourceBundle.getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.white")
                    : Lizzie.resourceBundle.getString("SGFParse.black"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                data.pda != 0
                    ? String.format(Locale.ENGLISH, "%.1f", data.getKomi())
                        + " "
                        + Lizzie.resourceBundle.getString("SGFParse.pda")
                        + data.pda
                    : String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      }
    } else {
      String wf =
          "%s "
              + Lizzie.resourceBundle.getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + Lizzie.resourceBundle.getString("SGFParse.playouts")
              + ")\n"
              + Lizzie.resourceBundle.getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? Lizzie.resourceBundle.getString("SGFParse.white")
                  : Lizzie.resourceBundle.getString("SGFParse.black"),
              String.format(Locale.ENGLISH, "%.1f%%", curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
    }
    return nc;
  }

  public static String formatCommentPk(BoardHistoryNode node) {
    if (!EngineManager.isSaveingEngineSGF && !node.previous().isPresent()) return "";
    BoardData data = node.getData();
    String engine = node.getData().engineName;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts());
    // Last winrate
    Optional<BoardData> lastNode;
    if (node.previous().isPresent())
      lastNode = node.previous().get().previous().flatMap(n -> Optional.of(n.getData()));
    else lastNode = Optional.empty();
    boolean validLastWinrate = lastNode.isPresent();
    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;
    boolean validWinrate = (data.getPlayouts() > 0);
    double curWR;
    curWR = validWinrate ? data.winrate : 100 - lastWR;
    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      double diff = curWR - lastWR;
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
    }
    boolean blackWinrate = data.blackToPlay;
    String nc = "";
    if (data.isKataData) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().previous().get().getData().getPlayouts() > 0) {
          double diff = data.scoreMean - node.previous().get().previous().get().getData().scoreMean;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData) {
        double score = data.scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      } else {
        double score = node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
        }
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s "
                + Lizzie.resourceBundle.getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", data.getKomi())
                    + getPdaWrnString(data.pda, data.wrn));
      }
    } else {
      String wf =
          "%s "
              + Lizzie.resourceBundle.getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + Lizzie.resourceBundle.getString("SGFParse.playouts")
              + ")\n"
              + Lizzie.resourceBundle.getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? Lizzie.resourceBundle.getString("SGFParse.black")
                  : Lizzie.resourceBundle.getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
    }

    if (!data.comment.isEmpty() && !EngineManager.isSaveingEngineSGF) {
      // [^\\(\\)/]*
      String wp = "";
      if (!data.isKataData) {
        wp =
            "("
                + Lizzie.resourceBundle.getString("SGFParse.black")
                + " |"
                + Lizzie.resourceBundle.getString("SGFParse.white")
                + " )"
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n\\("
                + ".*"
                + " / [0-9\\.]*[kmKM]* "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + "\\)\\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + ".*";
      } else {
        if (data.isSaiData)
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
        else
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)* "
                  + Lizzie.resourceBundle.getString("SGFParse.stdev")
                  + " [0-9\\.\\-+]*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
      }
      // if (Lizzie.leelaz.isKatago) wp = wp + "\n.*";
      if (data.comment.matches("(?s).*" + wp + "(?s).*")) {
        nc = data.comment.replaceAll(wp, nc);
      } else {
        nc = String.format(Locale.ENGLISH, "%s\n\n%s", data.comment, nc);
      }
    }
    return nc;
  }

  private static String getPdaWrnString(double pda, double wrn) {
    // TODO Auto-generated method stub
    String line = pda != 0 ? " " + Lizzie.resourceBundle.getString("SGFParse.pda") + pda : "";
    line += wrn != 0 ? " " + Lizzie.resourceBundle.getString("SGFParse.wrn") + wrn : "";
    return line;
  }

  private static String formatComment2(BoardHistoryNode node) {
    //    if (node.getData().commented2) return node.getData().comment;
    //    node.getData().commented2 = true;
    BoardData data = node.getData();
    String engine = node.getData().engineName2;
    engine = engine.replaceAll(" ", "");
    String playouts = Utils.getPlayoutsString(data.getPlayouts2());

    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.isPresent();

    double lastWR = validLastWinrate ? lastNode.get().getWinrate2() : 50;
    if (EngineManager.isEngineGame && node.moveNumberOfNode() > 2) {
      lastWR = 100 - lastWR;
    }
    boolean validWinrate = (data.getPlayouts2() > 0);
    double curWR;
    if (Lizzie.config.winrateAlwaysBlack) {
      curWR = validWinrate ? data.getWinrate2() : lastWR;
    } else {
      curWR = validWinrate ? data.getWinrate2() : 100 - lastWR;
    }

    String lastMoveDiff = "";
    if (validLastWinrate && validWinrate) {
      //	      if (Lizzie.config.handicapInsteadOfWinrate) {
      //	        double currHandicapedWR = Leelaz.winrateToHandicap(100 - curWR);
      //	        double lastHandicapedWR = Leelaz.winrateToHandicap(lastWR);
      //	        lastMoveDiff = String.format(Locale.ENGLISH,": %.2f", currHandicapedWR -
      // lastHandicapedWR);
      //	      } else {
      double diff;
      if (Lizzie.config.winrateAlwaysBlack) {
        diff = lastWR - curWR;
      } else {
        diff = 100 - lastWR - curWR;
      }
      lastMoveDiff =
          String.format(Locale.ENGLISH, "(%s%.1f%%)", diff > 0 ? "+" : "-", Math.abs(diff));
      //  }
    }
    boolean blackWinrate = !data.blackToPlay || Lizzie.config.winrateAlwaysBlack;
    String nc = "";

    if (data.isKataData2) {
      String diffScore = "";
      if (validLastWinrate && validWinrate) {
        if (node.previous().get().getData().getPlayouts() > 0) {
          double diff = data.scoreMean + node.previous().get().getData().scoreMean;
          if (Lizzie.config.winrateAlwaysBlack && Lizzie.board.getHistory().isBlacksTurn())
            diff = -diff;
          diffScore =
              String.format(Locale.ENGLISH, "(%s%.1f)", diff > 0 ? "+" : "-", Math.abs(diff));
        }
      }
      if (data.isSaiData2) {
        double score = -node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
          if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        } else if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                engine,
                playouts,
                String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      } else {
        double score = -node.getData().scoreMean;
        if (Lizzie.config.showKataGoScoreLeadWithKomi) {
          if (data.blackToPlay) {
            score = score - Lizzie.board.getHistory().getGameInfo().getKomi();
            // else score = -score;
          } else {
            score = score + Lizzie.board.getHistory().getGameInfo().getKomi();
            //   else score = -score;
          }
          if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        } else if (Lizzie.config.winrateAlwaysBlack && data.blackToPlay) score = -score;
        String wf =
            "%s "
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " %s %s\n"
                + (Lizzie.config.showKataGoScoreLeadWithKomi
                    ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                    : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                + " %s %s "
                + Lizzie.resourceBundle.getString("SGFParse.stdev")
                + " %s\n(%s / %s "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + ")\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + " %s";
        double scoreStdev = node.getData().scoreStdev2;
        nc =
            String.format(
                wf,
                blackWinrate
                    ? Lizzie.resourceBundle.getString("SGFParse.black")
                    : Lizzie.resourceBundle.getString("SGFParse.white"),
                String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
                lastMoveDiff,
                String.format(Locale.ENGLISH, "%.1f", score),
                diffScore,
                String.format(Locale.ENGLISH, "%.1f", scoreStdev),
                engine,
                playouts,
                data.pda2 != 0
                    ? String.format(Locale.ENGLISH, "%.1f", data.getKomi())
                        + " "
                        + Lizzie.resourceBundle.getString("SGFParse.pda")
                        + data.pda2
                    : String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
      }
    } else {
      String wf =
          "%s "
              + Lizzie.resourceBundle.getString("SGFParse.winrate")
              + " %s %s\n(%s / %s "
              + Lizzie.resourceBundle.getString("SGFParse.playouts")
              + ")\n"
              + Lizzie.resourceBundle.getString("SGFParse.komi")
              + " %s";

      nc =
          String.format(
              wf,
              blackWinrate
                  ? Lizzie.resourceBundle.getString("SGFParse.black")
                  : Lizzie.resourceBundle.getString("SGFParse.white"),
              String.format(Locale.ENGLISH, "%.1f%%", 100 - curWR),
              lastMoveDiff,
              engine,
              playouts,
              String.format(Locale.ENGLISH, "%.1f", data.getKomi()));
    }

    if (!data.comment.isEmpty()) {
      // [^\\(\\)/]*
      String wp = "";
      if (!data.isKataData) {
        wp =
            "("
                + Lizzie.resourceBundle.getString("SGFParse.black")
                + " |"
                + Lizzie.resourceBundle.getString("SGFParse.white")
                + " )"
                + Lizzie.resourceBundle.getString("SGFParse.winrate")
                + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n\\("
                + ".*"
                + " / [0-9\\.]*[kmKM]* "
                + Lizzie.resourceBundle.getString("SGFParse.playouts")
                + "\\)\\n"
                + Lizzie.resourceBundle.getString("SGFParse.komi")
                + ".*";
      } else {
        if (data.isSaiData)
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
        else
          wp =
              "("
                  + Lizzie.resourceBundle.getString("SGFParse.black")
                  + " |"
                  + Lizzie.resourceBundle.getString("SGFParse.white")
                  + " )"
                  + Lizzie.resourceBundle.getString("SGFParse.winrate")
                  + " [0-9\\.\\-]+%* \\(*[0-9.\\-+]*%*\\)*\n"
                  + (Lizzie.config.showKataGoScoreLeadWithKomi
                      ? Lizzie.resourceBundle.getString("SGFParse.leadWithKomi")
                      : Lizzie.resourceBundle.getString("SGFParse.leadJustScore"))
                  + " [0-9\\.\\-+]* \\(*[0-9.\\-+]*\\)* "
                  + Lizzie.resourceBundle.getString("SGFParse.stdev")
                  + " [0-9\\.\\-+]*\n\\("
                  + ".*"
                  + " / [0-9\\.]*[kmKM]* "
                  + Lizzie.resourceBundle.getString("SGFParse.playouts")
                  + "\\)\\n"
                  + Lizzie.resourceBundle.getString("SGFParse.komi")
                  + ".*";
      }
      // if (Lizzie.leelaz.isKatago) wp = wp + "\n.*";
      if (data.comment.matches("(?s).*" + wp + "(?s).*")) {
        nc = data.comment.replaceAll(wp, nc);
      } else {
        nc = String.format(Locale.ENGLISH, "%s\n\n%s", data.comment, nc);
      }
    }
    return nc;
  }

  /** Format Comment with following format: <Winrate> <Playouts> */
  private static String formatNodeData(BoardHistoryNode node) {
    BoardData data = node.getData();

    // Playouts
    String playouts = Utils.getPlayoutsString(data.getPlayouts());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.map(SGFParser::hasPrimaryAnalysisPayload).orElse(false);
    double lastWR = validLastWinrate ? lastNode.get().winrate : 50;

    // Current winrate
    boolean validWinrate = hasPrimaryAnalysisPayload(data);
    double curWR = validWinrate ? data.winrate : 100 - lastWR;
    String curWinrate = "";
    curWinrate = String.format(Locale.ENGLISH, "%.1f", 100 - curWR);

    return formatAnalysisPayload(
        data.engineName,
        curWinrate,
        playouts,
        data.isKataData,
        data.analysisHeaderSlots,
        data.scoreMean,
        data.scoreStdev,
        data.pda,
        data.bestMovesToString(),
        data.estimateArray);
  }

  private static String formatNodeData2(BoardHistoryNode node) {
    BoardData data = node.getData();

    // Playouts
    String playouts = Utils.getPlayoutsString(data.getPlayouts2());

    // Last winrate
    Optional<BoardData> lastNode = node.previous().flatMap(n -> Optional.of(n.getData()));
    boolean validLastWinrate = lastNode.map(SGFParser::hasSecondaryAnalysisPayload).orElse(false);
    double lastWR = validLastWinrate ? lastNode.get().winrate2 : 50;

    // Current winrate
    boolean validWinrate = hasSecondaryAnalysisPayload(data);
    double curWR = validWinrate ? data.winrate2 : 100 - lastWR;
    String curWinrate = "";
    curWinrate = String.format(Locale.ENGLISH, "%.1f", 100 - curWR);

    return formatAnalysisPayload(
        data.engineName2,
        curWinrate,
        playouts,
        data.isKataData2,
        data.analysisHeaderSlots2,
        data.scoreMean2,
        data.scoreStdev2,
        data.pda2,
        data.bestMovesToString2(),
        data.estimateArray2);
  }

  private static String formatAnalysisPayload(
      String engineName,
      String curWinrate,
      String playouts,
      boolean kataData,
      int headerSlots,
      double scoreMean,
      double scoreStdev,
      double pda,
      String bestMoves,
      ArrayList<Double> estimateArray) {
    StringBuilder header = new StringBuilder();
    header.append(engineName).append(" ").append(curWinrate).append(" ").append(playouts);
    if (kataData) {
      boolean hasExplicitHeaderSlots = headerSlots > 0;
      boolean includeScoreStdev = !hasExplicitHeaderSlots || headerSlots >= 5;
      boolean includePda = hasExplicitHeaderSlots ? headerSlots >= 6 : Double.compare(pda, 0) != 0;
      header.append(" ").append(formatAnalysisScalar(scoreMean));
      if (includeScoreStdev) {
        header.append(" ").append(formatAnalysisScalar(scoreStdev));
      }
      if (includePda) {
        header.append(" ").append(formatAnalysisScalar(pda));
      }
    }

    String detailLine = formatAnalysisDetailLine(bestMoves, estimateArray);
    if (Utils.isBlank(detailLine)) {
      return header.toString();
    }
    return header.append('\n').append(detailLine).toString();
  }

  private static String formatAnalysisDetailLine(
      String bestMoves, ArrayList<Double> estimateArray) {
    StringBuilder detailLine = new StringBuilder();
    if (!Utils.isBlank(bestMoves)) {
      detailLine.append(bestMoves);
    }
    if (estimateArray != null && !estimateArray.isEmpty()) {
      detailLine.append(" ownership");
      for (Double value : estimateArray) {
        detailLine.append(" ").append(value);
      }
    }
    return detailLine.toString();
  }

  private static String formatAnalysisScalar(double value) {
    return Double.toString(value);
  }

  public static boolean isListProperty(String key) {
    return asList(listProps).contains(key);
  }

  public static boolean isMarkupProperty(String key) {
    return asList(markupProps).contains(key);
  }

  /**
   * Get a value with key, or the default if there is no such key
   *
   * @param key
   * @param defaultValue
   * @return
   */
  public static String getOrDefault(Map<String, String> props, String key, String defaultValue) {
    return props.getOrDefault(key, defaultValue);
  }

  /**
   * Add a key and value to the props
   *
   * @param key
   * @param value
   */
  public static void addProperty(Map<String, String> props, String key, String value) {
    if (SGFParser.isListProperty(key)) {
      // Label and add/remove stones
      props.merge(key, value, (old, val) -> old + "," + val);
    } else {
      props.put(key, value);
    }
  }

  /**
   * Add the properties by mutating the props
   *
   * @return
   */
  public static void addProperties(Map<String, String> props, Map<String, String> addProps) {
    addProps.forEach((key, value) -> addProperty(props, key, value));
  }

  /**
   * Add the properties from string
   *
   * @return
   */
  public static void addProperties(Map<String, String> props, String propsStr) {
    boolean inTag = false, escaping = false;
    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();

    for (int i = 0; i < propsStr.length(); i++) {
      char c = propsStr.charAt(i);
      if (escaping) {
        tagContentBuilder.append(c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (inTag) {
            if (i > 0) {
              tagContentBuilder.append(c);
            }
          }
          break;
        case ';':
        case ')':
          if (inTag) {
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          inTag = true;
          String tagTemp = tagBuilder.toString();
          if (!tagTemp.isEmpty()) {
            tag = tagTemp.replaceAll("[a-z]", "");
          }
          tagContentBuilder = new StringBuilder();
          break;
        case ']':
          inTag = false;
          tagBuilder = new StringBuilder();
          addProperty(props, tag, tagContentBuilder.toString());
          break;
        default:
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C")) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
  }

  public static String getResult(String filename) {
    File file = new File(filename);
    if (!file.exists() || !file.canRead()) {
      return "";
    }
    String encoding = EncodingDetector.detect(filename);
    try (FileInputStream fp = new FileInputStream(file);
        InputStreamReader reader =
            new InputStreamReader(fp, encoding.equals("WINDOWS-1252") ? "GB18030" : encoding)) {
      StringBuilder builder = new StringBuilder();
      while (reader.ready()) {
        builder.append((char) reader.read());
      }
      String value = builder.toString();
      if (value.isEmpty()) {
        Lizzie.board.isLoadingFile = false;
        return "";
      }
      return parseResult(value);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  private static String parseResult(String value) {
    int tempIndex = value.indexOf("RE[") + 3;
    String temp = value.substring(tempIndex, Math.min(value.length(), tempIndex + 20));
    return temp.substring(0, temp.indexOf("]"));
  }

  /**
   * Get properties string by the props
   *
   * @return
   */
  public static String propertiesString(Map<String, String> props) {
    StringBuilder sb = new StringBuilder();
    props.forEach((key, value) -> sb.append(nodeString(key, value)));
    return sb.toString();
  }

  /**
   * Get node string by the key and value
   *
   * @param key
   * @param value
   * @return
   */
  public static String nodeString(String key, String value) {
    StringBuilder sb = new StringBuilder();
    if (SGFParser.isListProperty(key)) {
      // Label and add/remove stones
      sb.append(key);
      String[] vals = value.split(",");
      for (String val : vals) {
        sb.append("[").append(val).append("]");
      }
    } else {
      sb.append(key).append("[").append(value).append("]");
    }
    return sb.toString();
  }

  private static void processPendingPros(BoardHistoryList history, Map<String, String> props) {
    props.forEach((key, value) -> history.addNodeProperty(key, value));
    props = new HashMap<String, String>();
  }

  private static final class PendingSetupMetadata {
    private String comment;
    private final Map<String, String> properties = new LinkedHashMap<String, String>();
    private final List<PendingAnalysisPayload> analysisPayloads =
        new ArrayList<PendingAnalysisPayload>();

    private boolean isEmpty() {
      return Utils.isBlank(comment) && properties.isEmpty() && analysisPayloads.isEmpty();
    }

    private void clear() {
      comment = null;
      properties.clear();
      analysisPayloads.clear();
    }
  }

  private static final class PendingAnalysisPayload {
    private final String tag;
    private final String content;

    private PendingAnalysisPayload(String tag, String content) {
      this.tag = tag;
      this.content = content;
    }
  }

  private static boolean isLeadingSetupMetadataTag(String tag) {
    return "C".equals(tag) || "MN".equals(tag) || isMarkupProperty(tag);
  }

  private static boolean shouldBufferLeadingSetupMetadata(
      boolean currentNodeHasMove,
      boolean currentNodeHasSetupSnapshot,
      boolean rootSetupContext,
      String tag) {
    return !currentNodeHasMove
        && !currentNodeHasSetupSnapshot
        && !rootSetupContext
        && isLeadingSetupMetadataTag(tag);
  }

  private static void bufferLeadingSetupMetadata(
      PendingSetupMetadata pendingSetupMetadata, String tag, String tagContent) {
    if ("C".equals(tag)) {
      pendingSetupMetadata.comment = tagContent;
      return;
    }
    addProperty(pendingSetupMetadata.properties, tag, tagContent);
  }

  private static boolean shouldBufferLeadingSetupAnalysis(
      boolean currentNodeHasMove, boolean currentNodeHasSetupSnapshot, boolean rootSetupContext) {
    return !currentNodeHasMove && !currentNodeHasSetupSnapshot && !rootSetupContext;
  }

  private static void bufferLeadingSetupAnalysis(
      PendingSetupMetadata pendingSetupMetadata, String tag, String tagContent) {
    pendingSetupMetadata.analysisPayloads.add(new PendingAnalysisPayload(tag, tagContent));
  }

  private static void applyPendingSetupAnalysisPayloads(
      BoardData target, PendingSetupMetadata pendingSetupMetadata) {
    if (target == null || pendingSetupMetadata.analysisPayloads.isEmpty()) {
      return;
    }
    pendingSetupMetadata.analysisPayloads.forEach(
        payload -> applyAnalysisTagOnDetachedHistory(target, payload.tag, payload.content));
  }

  private static void flushPendingSetupMetadataToCurrentNode(
      BoardHistoryList history, PendingSetupMetadata pendingSetupMetadata) {
    if (pendingSetupMetadata == null || pendingSetupMetadata.isEmpty()) {
      return;
    }
    BoardHistoryNode current = history.getCurrentHistoryNode();
    if (!Utils.isBlank(pendingSetupMetadata.comment)) {
      current.getData().comment = pendingSetupMetadata.comment;
    }
    pendingSetupMetadata.properties.forEach(history::addNodeProperty);
    applyPendingSetupAnalysisPayloads(current.getData(), pendingSetupMetadata);
    pendingSetupMetadata.clear();
  }

  private static void movePendingSetupMetadataToTarget(
      BoardHistoryList history,
      BoardHistoryNode target,
      PendingSetupMetadata pendingSetupMetadata) {
    if (pendingSetupMetadata == null || pendingSetupMetadata.isEmpty()) {
      return;
    }
    if (!Utils.isBlank(pendingSetupMetadata.comment)) {
      target.getData().comment = pendingSetupMetadata.comment;
    }
    pendingSetupMetadata.properties.forEach(
        (key, value) -> addNodePropertyOnNode(history, target, key, value));
    applyPendingSetupAnalysisPayloads(target.getData(), pendingSetupMetadata);
    pendingSetupMetadata.clear();
  }

  private static void moveLeadingSetupMetadataToSnapshot(BoardHistoryList history) {
    BoardHistoryNode snapshotNode = history.getCurrentHistoryNode();
    if (!snapshotNode.getData().isSnapshotNode() || !snapshotNode.previous().isPresent()) {
      return;
    }
    BoardHistoryNode moveNode = snapshotNode.previous().get();
    BoardData moveData = moveNode.getData();
    BoardData snapshotData = snapshotNode.getData();
    if (!moveData.comment.isEmpty()) {
      snapshotData.comment = moveData.comment;
      moveData.comment = "";
    }
    moveLeadingAnalysisPayloadToSnapshot(moveData, snapshotData);
    if (moveData.getProperties().isEmpty()) {
      return;
    }
    Map<String, String> properties = new LinkedHashMap<String, String>(moveData.getProperties());
    properties.forEach(snapshotData::addProperty);
    moveData.setProperties(new LinkedHashMap<String, String>());
    if (properties.containsKey("PL")) {
      applyCurrentPlayerProperty(snapshotData, properties.get("PL"));
    }
    if (properties.containsKey("MN")) {
      rollbackMoveNodeMnState(moveNode);
    }
  }

  private static void moveLeadingAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    movePrimaryAnalysisPayloadToSnapshot(moveData, snapshotData);
    moveSecondaryAnalysisPayloadToSnapshot(moveData, snapshotData);
  }

  private static void movePrimaryAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    if (!hasPrimaryAnalysisPayload(moveData)) {
      return;
    }
    snapshotData.engineName = moveData.engineName;
    snapshotData.winrate = moveData.winrate;
    snapshotData.setPlayouts(moveData.getPlayouts());
    snapshotData.bestMoves = copyMoveDataListAllowNull(moveData.bestMoves);
    snapshotData.bestMovesOutOfRange = copyMoveDataListAllowNull(moveData.bestMovesOutOfRange);
    snapshotData.estimateArray = copyEstimateArray(moveData.estimateArray);
    snapshotData.isSaiData = moveData.isSaiData;
    snapshotData.isKataData = moveData.isKataData;
    snapshotData.isChanged = moveData.isChanged;
    snapshotData.playoutsChanged = moveData.playoutsChanged;
    snapshotData.analysisHeaderSlots = moveData.analysisHeaderSlots;
    snapshotData.scoreMean = moveData.scoreMean;
    snapshotData.scoreStdev = moveData.scoreStdev;
    snapshotData.pda = moveData.pda;
    clearPrimaryAnalysisPayload(moveData);
  }

  private static void moveSecondaryAnalysisPayloadToSnapshot(
      BoardData moveData, BoardData snapshotData) {
    if (!hasSecondaryAnalysisPayload(moveData)) {
      return;
    }
    snapshotData.engineName2 = moveData.engineName2;
    snapshotData.winrate2 = moveData.winrate2;
    snapshotData.setPlayouts2(moveData.getPlayouts2());
    snapshotData.bestMoves2 = copyMoveDataListAllowNull(moveData.bestMoves2);
    snapshotData.bestMoves2OutOfRange = copyMoveDataListAllowNull(moveData.bestMoves2OutOfRange);
    snapshotData.estimateArray2 = copyEstimateArray(moveData.estimateArray2);
    snapshotData.isSaiData2 = moveData.isSaiData2;
    snapshotData.isKataData2 = moveData.isKataData2;
    snapshotData.isChanged2 = moveData.isChanged2;
    snapshotData.analysisHeaderSlots2 = moveData.analysisHeaderSlots2;
    snapshotData.scoreMean2 = moveData.scoreMean2;
    snapshotData.scoreStdev2 = moveData.scoreStdev2;
    snapshotData.pda2 = moveData.pda2;
    clearSecondaryAnalysisPayload(moveData);
  }

  private static boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data.getPlayouts() > 0
        || !Utils.isBlank(data.engineName)
        || (data.bestMoves != null && !data.bestMoves.isEmpty())
        || data.isKataData
        || (data.estimateArray != null && !data.estimateArray.isEmpty());
  }

  private static boolean hasSecondaryAnalysisPayload(BoardData data) {
    return data.getPlayouts2() > 0
        || !Utils.isBlank(data.engineName2)
        || (data.bestMoves2 != null && !data.bestMoves2.isEmpty())
        || data.isKataData2
        || (data.estimateArray2 != null && !data.estimateArray2.isEmpty());
  }

  private static void clearPrimaryAnalysisPayload(BoardData data) {
    data.engineName = "";
    data.winrate = 50;
    data.setPlayouts(0);
    data.bestMoves = new ArrayList<MoveData>();
    data.bestMovesOutOfRange = new ArrayList<MoveData>();
    data.estimateArray = null;
    data.isSaiData = false;
    data.isKataData = false;
    data.isChanged = false;
    data.playoutsChanged = false;
    data.analysisHeaderSlots = 0;
    data.scoreMean = 0;
    data.scoreStdev = 0;
    data.pda = 0;
  }

  private static void clearSecondaryAnalysisPayload(BoardData data) {
    data.engineName2 = "";
    data.winrate2 = 50;
    data.setPlayouts2(0);
    data.bestMoves2 = new ArrayList<MoveData>();
    data.bestMoves2OutOfRange = new ArrayList<MoveData>();
    data.estimateArray2 = null;
    data.isSaiData2 = false;
    data.isKataData2 = false;
    data.isChanged2 = false;
    data.analysisHeaderSlots2 = 0;
    data.scoreMean2 = 0;
    data.scoreStdev2 = 0;
    data.pda2 = 0;
  }

  private static List<MoveData> copyMoveDataListAllowNull(List<MoveData> values) {
    return values == null ? null : new ArrayList<MoveData>(values);
  }

  private static ArrayList<Double> copyEstimateArray(ArrayList<Double> values) {
    return values == null ? null : new ArrayList<Double>(values);
  }

  private static void rollbackMoveNodeMnState(BoardHistoryNode moveNode) {
    BoardData moveData = moveNode.getData();
    if (moveData.isMoveNode()) {
      moveNode.resetMoveNumberList(moveData.moveNumber);
    }
    moveData.moveMNNumber = -1;
  }

  private static boolean isRootSetupContext(
      BoardHistoryList history, boolean moveStart, boolean allowRootSetupOnCurrentNode) {
    return allowRootSetupOnCurrentNode
        && !moveStart
        && history != null
        && !history.getCurrentHistoryNode().previous().isPresent();
  }

  private static boolean isRootSetupContext(BoardHistoryList history, boolean moveStart) {
    return isRootSetupContext(history, moveStart, true);
  }

  private static BoardHistoryNode getSetupPropertyTargetNode(
      BoardHistoryList history, boolean currentNodeHasSetupSnapshot) {
    BoardHistoryNode current = history.getCurrentHistoryNode();
    if (!currentNodeHasSetupSnapshot || !current.getData().isHistoryActionNode()) {
      return current;
    }
    if (!current.previous().isPresent()) {
      return current;
    }
    BoardHistoryNode previous = current.previous().get();
    if (!previous.getData().isSnapshotNode()) {
      return current;
    }
    return previous;
  }

  private static void runOnNode(
      BoardHistoryList history, BoardHistoryNode target, Runnable action) {
    BoardHistoryNode original = history.getCurrentHistoryNode();
    if (original == target) {
      action.run();
      return;
    }
    history.setHead(target);
    try {
      action.run();
    } finally {
      history.setHead(original);
    }
  }

  private static void addNodePropertyOnNode(
      BoardHistoryList history, BoardHistoryNode target, String key, String value) {
    runOnNode(history, target, () -> history.addNodeProperty(key, value));
  }

  private static void addExtraStoneOnNode(
      BoardHistoryList history, BoardHistoryNode target, int x, int y, Stone color) {
    runOnNode(history, target, () -> history.addExtraStone(x, y, color));
  }

  private static void removeStoneOnNode(
      BoardHistoryList history, BoardHistoryNode target, int x, int y, Stone color) {
    removeStoneOnNode(history, target, x, y, color, true);
  }

  private static void removeStoneOnNode(
      BoardHistoryList history,
      BoardHistoryNode target,
      int x,
      int y,
      Stone color,
      boolean refreshUi) {
    runOnNode(history, target, () -> removeStone(history, x, y, color, refreshUi));
  }

  private static void removeStone(
      BoardHistoryList history, int x, int y, Stone color, boolean refreshUi) {
    if (refreshUi) {
      history.removeStone(x, y, color);
      return;
    }
    removeStoneWithoutRefresh(history, x, y);
  }

  private static void removeStoneWithoutRefresh(BoardHistoryList history, int x, int y) {
    synchronized (history) {
      if (!Board.isValid(x, y)) {
        return;
      }
      int index = Board.getIndex(x, y);
      Stone[] stones = history.getStones();
      if (stones[index] == Stone.EMPTY) {
        return;
      }
      BoardData data = history.getData();
      Stone oriColor = stones[index];
      stones[index] = Stone.EMPTY;
      data.zobrist.toggleStone(x, y, oriColor);
      data.moveNumberList[index] = 0;
      history.getCurrentHistoryNode().setRemovedStone();
    }
  }

  private static void applyCurrentPlayerPropertyOnNode(
      BoardHistoryList history, BoardHistoryNode target, String tagContent) {
    runOnNode(history, target, () -> applyCurrentPlayerProperty(history, tagContent));
  }

  private static void clearStartStoneState(BoardHistoryList targetHistory) {
    if (!isCurrentBoardHistory(targetHistory)) {
      return;
    }
    Lizzie.board.hasStartStone = false;
    if (Lizzie.board.startStonelist == null) {
      Lizzie.board.startStonelist = new ArrayList<Movelist>();
      return;
    }
    Lizzie.board.startStonelist.clear();
  }

  private static boolean isCurrentBoardHistory(BoardHistoryList history) {
    return Lizzie.board != null && history != null && Lizzie.board.getHistory() == history;
  }

  private static void applyRootSetupProperty(
      BoardHistoryList history, String tag, String tagContent, int[] move, Stone color) {
    applyRootSetupProperty(history, tag, tagContent, move, color, true);
  }

  private static void applyRootSetupProperty(
      BoardHistoryList history,
      String tag,
      String tagContent,
      int[] move,
      Stone color,
      boolean refreshUi) {
    clearStartStoneState(history);
    history.addNodeProperty(tag, tagContent);
    if (move == null) {
      return;
    }
    if ("AE".equals(tag)) {
      removeStone(history, move[0], move[1], color, refreshUi);
      return;
    }
    if (history.getStones()[Board.getIndex(move[0], move[1])] == Stone.EMPTY) {
      history.addExtraStone(move[0], move[1], color);
    }
  }

  private static void applyRootPlayerProperty(BoardHistoryList history, String tagContent) {
    clearStartStoneState(history);
    applyCurrentPlayerProperty(history, tagContent);
  }

  private static void applyCurrentPlayerProperty(BoardHistoryList history, String tagContent) {
    history.addNodeProperty("PL", tagContent);
    applyCurrentPlayerProperty(history.getData(), tagContent);
  }

  private static void applyCurrentPlayerProperty(BoardData data, String tagContent) {
    data.blackToPlay = !"W".equalsIgnoreCase(tagContent);
  }

  private static void stabilizeRootSetupSideToPlay(BoardHistoryList history) {
    if (history == null) {
      return;
    }
    BoardData rootData = history.getStart().getData();
    String explicitPl = rootData.getProperty("PL");
    if (!Utils.isBlank(explicitPl)) {
      applyCurrentPlayerProperty(rootData, explicitPl);
      return;
    }
    if (hasRootSetupStoneProperties(rootData)) {
      rootData.blackToPlay = !isHandicapRootSetup(history, rootData);
    }
  }

  private static boolean hasRootSetupStoneProperties(BoardData rootData) {
    Map<String, String> properties = rootData.getProperties();
    return properties.containsKey("AB")
        || properties.containsKey("AW")
        || properties.containsKey("AE");
  }

  private static boolean isHandicapRootSetup(BoardHistoryList history, BoardData rootData) {
    if (rootData == null || !rootData.getProperties().containsKey("AB")) {
      return false;
    }
    int handicap =
        history == null || history.getGameInfo() == null ? 0 : history.getGameInfo().getHandicap();
    if (handicap <= 0) {
      handicap = parseHandicapProperty(rootData.getProperty("HA"));
    }
    return handicap >= 2;
  }

  private static int parseHandicapProperty(String rawHandicap) {
    if (Utils.isBlank(rawHandicap)) {
      return 0;
    }
    try {
      return Integer.parseInt(rawHandicap.trim());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static void appendSetupSnapshot(
      BoardHistoryList history,
      boolean newBranch,
      Map<String, String> pendingProps,
      boolean transferLeadingMoveMetadata) {
    BoardData current = history.getData();
    int snapshotPlayouts = transferLeadingMoveMetadata ? current.getPlayouts() : 0;
    double snapshotWinrate = transferLeadingMoveMetadata ? current.winrate : 50;
    BoardData snapshot =
        BoardData.snapshot(
            current.stones.clone(),
            Optional.empty(),
            Stone.EMPTY,
            current.blackToPlay,
            current.zobrist.clone(),
            current.moveNumber,
            current.moveNumberList.clone(),
            current.blackCaptures,
            current.whiteCaptures,
            snapshotWinrate,
            snapshotPlayouts);
    if (!transferLeadingMoveMetadata) {
      clearPrimaryAnalysisPayload(snapshot);
      clearSecondaryAnalysisPayload(snapshot);
    }
    snapshot.moveMNNumber = current.moveMNNumber;
    history.addOrGoto(snapshot, newBranch);
    if (transferLeadingMoveMetadata) {
      moveLeadingSetupMetadataToSnapshot(history);
    }
    if (newBranch) {
      processPendingPros(history, pendingProps);
    }
  }

  public static String Escaping(String in) {
    String out = in.replaceAll("\\\\", "\\\\\\\\");
    return out.replaceAll("\\]", "\\\\]");
  }

  public static BoardHistoryList parseSgf(String value, boolean first) {
    BoardHistoryList history = null;

    // Drop anything outside "(;...)"
    final Pattern SGF_PATTERN = Pattern.compile("(?s).*?(\\(\\s*;{0,1}.*\\))(?s).*?");
    Matcher sgfMatcher = SGF_PATTERN.matcher(value);
    if (sgfMatcher.matches()) {
      value = sgfMatcher.group(1);
    } else {
      return history;
    }

    int[] boardSize = parseBoardSize(value);
    int boardWidth = boardSize[0];
    int boardHeight = boardSize[1];
    ParseBoardContext boardContext = captureParseBoardContext();
    boolean switchedBoardContext =
        boardContext.boardWidth != boardWidth || boardContext.boardHeight != boardHeight;
    try {
      if (switchedBoardContext) {
        applyParseBoardContext(boardWidth, boardHeight);
      }
      history = new BoardHistoryList(BoardData.empty(boardWidth, boardHeight));
      parseValue(value, history, false, first);
    } finally {
      if (switchedBoardContext) {
        restoreParseBoardContext(boardContext);
      }
    }

    return history;
  }

  private static ParseBoardContext captureParseBoardContext() {
    return new ParseBoardContext(
        Board.boardWidth,
        Board.boardHeight,
        readZobristTable(BLACK_ZOBRIST_FIELD),
        readZobristTable(WHITE_ZOBRIST_FIELD));
  }

  private static void applyParseBoardContext(int boardWidth, int boardHeight) {
    Board.boardWidth = boardWidth;
    Board.boardHeight = boardHeight;
    Zobrist.init();
  }

  private static void restoreParseBoardContext(ParseBoardContext context) {
    Board.boardWidth = context.boardWidth;
    Board.boardHeight = context.boardHeight;
    writeZobristTable(BLACK_ZOBRIST_FIELD, context.blackZobrist);
    writeZobristTable(WHITE_ZOBRIST_FIELD, context.whiteZobrist);
  }

  private static Field resolveZobristField(String fieldName) {
    try {
      Field field = Zobrist.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Failed to access Zobrist." + fieldName, ex);
    }
  }

  private static long[] readZobristTable(Field field) {
    try {
      return (long[]) field.get(null);
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException("Failed to read Zobrist table.", ex);
    }
  }

  private static void writeZobristTable(Field field, long[] table) {
    try {
      field.set(null, table);
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException("Failed to restore Zobrist table.", ex);
    }
  }

  private static final class ParseBoardContext {
    private final int boardWidth;
    private final int boardHeight;
    private final long[] blackZobrist;
    private final long[] whiteZobrist;

    private ParseBoardContext(
        int boardWidth, int boardHeight, long[] blackZobrist, long[] whiteZobrist) {
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.blackZobrist = blackZobrist;
      this.whiteZobrist = whiteZobrist;
    }
  }

  private static int[] parseBoardSize(String value) {
    int boardWidth = DEFAULT_BOARD_SIZE;
    int boardHeight = DEFAULT_BOARD_SIZE;
    Matcher szMatcher = BOARD_SIZE_PATTERN.matcher(value);
    if (!szMatcher.matches()) {
      return new int[] {boardWidth, boardHeight};
    }
    int[] parsedBoardSize = parseBoardSizeTagValue(szMatcher.group(1));
    if (parsedBoardSize != null) {
      return parsedBoardSize;
    }
    return new int[] {boardWidth, boardHeight};
  }

  static int[] resolveHistoryBoardSize(BoardHistoryList history) {
    if (history == null) {
      return new int[] {Board.boardWidth, Board.boardHeight};
    }
    return resolveBoardSize(history.getStart().getData(), Board.boardWidth, Board.boardHeight);
  }

  private static int[] resolveNodeBoardSize(BoardHistoryNode node) {
    if (node == null) {
      return new int[] {Board.boardWidth, Board.boardHeight};
    }
    BoardHistoryNode root = node;
    while (root.previous().isPresent()) {
      root = root.previous().get();
    }
    return resolveBoardSize(root.getData(), Board.boardWidth, Board.boardHeight);
  }

  private static int[] resolveBoardSize(
      BoardData rootData, int fallbackBoardWidth, int fallbackBoardHeight) {
    if (rootData == null) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    int[] parsedFromSz = parseBoardSizeTagValue(rootData.getProperty("SZ"));
    if (parsedFromSz != null) {
      return parsedFromSz;
    }
    int boardArea = rootData.stones == null ? 0 : rootData.stones.length;
    if (boardArea <= 0) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    if (fallbackBoardWidth > 0
        && fallbackBoardHeight > 0
        && fallbackBoardWidth * fallbackBoardHeight == boardArea) {
      return new int[] {fallbackBoardWidth, fallbackBoardHeight};
    }
    if (fallbackBoardHeight > 0 && boardArea % fallbackBoardHeight == 0) {
      return new int[] {boardArea / fallbackBoardHeight, fallbackBoardHeight};
    }
    if (fallbackBoardWidth > 0 && boardArea % fallbackBoardWidth == 0) {
      return new int[] {fallbackBoardWidth, boardArea / fallbackBoardWidth};
    }
    int squareBoardSize = (int) Math.sqrt(boardArea);
    if (squareBoardSize * squareBoardSize == boardArea) {
      return new int[] {squareBoardSize, squareBoardSize};
    }
    return new int[] {boardArea, 1};
  }

  private static int[] parseBoardSizeTagValue(String rawSzValue) {
    if (rawSzValue == null) {
      return null;
    }
    String normalizedValue = rawSzValue.split(",")[0].trim();
    if (normalizedValue.isEmpty()) {
      return null;
    }
    Matcher rectSizeMatcher = RECT_BOARD_SIZE_PATTERN.matcher(normalizedValue);
    try {
      if (rectSizeMatcher.matches()) {
        int boardWidth = Integer.parseInt(rectSizeMatcher.group(1));
        int boardHeight = Integer.parseInt(rectSizeMatcher.group(2));
        if (boardWidth > 0 && boardHeight > 0) {
          return new int[] {boardWidth, boardHeight};
        }
        return null;
      }
      int squareBoardSize = Integer.parseInt(normalizedValue);
      if (squareBoardSize <= 0) {
        return null;
      }
      return new int[] {squareBoardSize, squareBoardSize};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String formatBoardSizeTag(int boardWidth, int boardHeight) {
    return boardWidth + (boardWidth != boardHeight ? ":" + boardHeight : "");
  }

  private static BoardHistoryList parseValue(
      String value, BoardHistoryList history, boolean isBranch, boolean firstTime) {

    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, BoardHistoryNode> subTreeStepMap = new HashMap<Integer, BoardHistoryNode>();
    // Comment of the game head
    String headComment = "";
    // Game properties
    Map<String, String> gameProperties = new HashMap<String, String>();
    Map<String, String> pendingProps = new HashMap<String, String>();
    PendingSetupMetadata pendingSetupMetadata = new PendingSetupMetadata();
    boolean inTag = false,
        isMultiGo = false,
        escaping = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    boolean inProp = false;
    int topLevelNodeCount = 0;
    boolean allowRootSetupOnCurrentNode = false;
    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();
    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    if (value.matches("(?s).*\\)\\s*\\)")) {
      isMultiGo = true;
    }
    if (isBranch) {
      subTreeDepth += 1;
      // Initialize the step count
      subTreeStepMap.put(subTreeDepth, history.getCurrentHistoryNode());
    }

    String blackPlayer = "", whitePlayer = "";

    // Support unicode characters (UTF-8)
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (escaping) {
        // Any char following "\" is inserted verbatim
        // (ref) "3.2. Text" in https://www.red-bean.com/sgf/sgf4.html
        tagContentBuilder.append(c == 'n' ? "\n" : c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            subTreeDepth += 1;
            // Initialize the step count
            subTreeStepMap.put(subTreeDepth, history.getCurrentHistoryNode());
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
            pendingProps = new HashMap<String, String>();
          } else {
            if (i > 0) {
              // Allow the comment tag includes '('
              tagContentBuilder.append(c);
            }
          }
          break;
        case ')':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            if (isMultiGo) {
              // Restore to the variation node
              // int varStep = subTreeStepMap.get(subTreeDepth);
              //   for (int s = 0; s < varStep; s++)
              while (history.getCurrentHistoryNode() != subTreeStepMap.get(subTreeDepth)) {
                if (!history.previous().isPresent()) break;
              }
            }
            subTreeDepth -= 1;
          } else {
            // Allow the comment tag includes '('
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          if (!inProp) {
            inProp = true;
            if (subTreeDepth > 1 && !isMultiGo) {
              break;
            }
            inTag = true;
            String tagTemp = tagBuilder.toString();
            if (!tagTemp.isEmpty()) {
              // Ignore small letters in tags for the long format Smart-Go file.
              // (ex) "PlayerBlack" ==> "PB"
              // It is the default format of mgt, an old SGF tool.
              // (Mgt is still supported in Debian and Ubuntu.)
              tag = tagTemp.replaceAll("[a-z]", "");
            }
            tagContentBuilder = new StringBuilder();
          } else {
            tagContentBuilder.append(c);
          }
          break;
        case ']':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          inTag = false;
          inProp = false;
          tagBuilder = new StringBuilder();
          String tagContent = tagContentBuilder.toString();
          // We got tag, we can parse this tag now.
          if (tag.equals("B") || tag.equals("W")) {
            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent);
            // Save the step count
            //  subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            // boolean newBranch = (subTreeStepMap.get(subTreeDepth) == 1);
            boolean newBranch = history.getCurrentHistoryNode().hasVariations();
            if (move == null) {
              history.pass(color, newBranch, false);

            } else {
              history.place(move[0], move[1], color, newBranch);
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(history, pendingProps);
            }
          } else if (tag.equals("C")) {
            // Support comment
            boolean rootSetupContext =
                isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
            if (rootSetupContext) {
              headComment = tagContent;
            } else if (shouldBufferLeadingSetupMetadata(
                currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
              bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (isAnalysisTag(tag)) {
            boolean rootSetupContext =
                isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
            if (shouldBufferLeadingSetupAnalysis(
                currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext)) {
              bufferLeadingSetupAnalysis(pendingSetupMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              applyAnalysisTagOnDetachedHistory(target.getData(), tag, tagContent);
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent);
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(history, tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
              addNodePropertyOnNode(history, target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(history, target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(history, tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
              applyCurrentPlayerPropertyOnNode(history, target, tagContent);
            }
          } else if (tag.equals("PB")) {
            blackPlayer = tagContent;
            history.getGameInfo().setPlayerBlack(blackPlayer);
          } else if (tag.equals("PW")) {
            whitePlayer = tagContent;
            history.getGameInfo().setPlayerWhite(whitePlayer);
          } else if (tag.equals("KM") && Lizzie.config.readKomi) {
            if (firstTime) {
              try {
                if (!tagContent.trim().isEmpty()) {
                  Double komi = Double.parseDouble(tagContent);
                  if (komi >= 200) {
                    komi = komi / 100;
                    if (komi <= 4 && komi >= -4) komi = komi * 2;
                  }
                  if (komi.toString().endsWith(".75") || komi.toString().endsWith(".25"))
                    komi = komi * 2;
                  if (Math.abs(komi) < Board.boardWidth * Board.boardHeight) {
                    history.getGameInfo().setKomiNoMenu(komi);
                  }
                }
              } catch (NumberFormatException e) {
                e.printStackTrace();
              }
            }
          } else if (tag.equals("HA")) {
            try {
              if (tagContent.trim().isEmpty()) {
                tagContent = "0";
              }
              int handicap = Integer.parseInt(tagContent);
              history.getGameInfo().setHandicap(handicap);
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          } else {
            if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY, false);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, false, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else {
                boolean firstProp = history.getCurrentHistoryNode().hasVariations();
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  history.addNodeProperty(tag, tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent);
                applyRootSetupProperty(history, tag, tagContent, move, Stone.EMPTY, false);
              } else if (rootSetupContext) {
                if ("N".equals(tag) && headComment.isEmpty()) headComment = tagContent;
                else addProperty(gameProperties, tag, tagContent);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch = history.getCurrentHistoryNode().hasVariations();
                  appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY, false);
                }
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (inProp) {
            // support C[a;b;c;]
            tagContentBuilder.append(c);
          } else if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            topLevelNodeCount += 1;
            allowRootSetupOnCurrentNode = !isBranch && topLevelNodeCount == 1;
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C")) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
    flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);

    if (isBranch) {
      history.toBranchTop();
    } else {
      if (!Utils.isBlank(gameProperties.get("RE")) && Utils.isBlank(history.getData().comment)) {
        history.getData().comment = gameProperties.get("RE");
      }

      // Rewind to game start
      while (history.previous().isPresent())
        ;

      // Set AW/AB Comment
      if (!headComment.isEmpty()) {
        history.getData().comment = headComment;
      }
      if (gameProperties.size() > 0) {
        history.getData().addProperties(gameProperties);
      }
      stabilizeRootSetupSideToPlay(history);
    }
    return history;
  }

  public static void addStartList(boolean isBlack, int x, int y) {
    Movelist move = new Movelist();
    move.x = x;
    move.y = y;
    move.ispass = false;
    move.isblack = isBlack;
    move.movenum = Lizzie.board.startStonelist.size() + 1;
    Lizzie.board.startStonelist.add(move);
  }

  public static int parseBranch(BoardHistoryList history, String value) {
    int subTreeDepth = 0;
    // Save the variation step count
    Map<Integer, Integer> subTreeStepMap = new HashMap<Integer, Integer>();
    // Comment of the game head
    String headComment = "";
    // Game properties
    Map<String, String> gameProperties = new HashMap<String, String>();
    Map<String, String> pendingProps = new HashMap<String, String>();
    PendingSetupMetadata pendingSetupMetadata = new PendingSetupMetadata();
    boolean inTag = false,
        isMultiGo = false,
        escaping = false,
        moveStart = false,
        currentNodeHasMove = false,
        addPassForMove = true,
        currentNodeHasSetupSnapshot = false;
    boolean inProp = false;
    boolean allowRootSetupOnCurrentNode = false;
    String tag = "";
    StringBuilder tagBuilder = new StringBuilder();
    StringBuilder tagContentBuilder = new StringBuilder();
    // MultiGo 's branch: (Main Branch (Main Branch) (Branch) )
    // Other 's branch: (Main Branch (Branch) Main Branch)
    if (value.matches("(?s).*\\)\\s*\\)")) {
      isMultiGo = true;
    }
    subTreeDepth += 1;
    // Initialize the step count
    subTreeStepMap.put(subTreeDepth, 0);

    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (escaping) {
        tagContentBuilder.append(c == 'n' ? "\n" : c);
        escaping = false;
        continue;
      }
      switch (c) {
        case '(':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            subTreeDepth += 1;
            // Initialize the step count
            subTreeStepMap.put(subTreeDepth, 0);
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
            pendingProps = new HashMap<String, String>();
          } else {
            if (i > 0) {
              // Allow the comment tag includes '('
              tagContentBuilder.append(c);
            }
          }
          break;
        case ')':
          if (!inTag) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            if (isMultiGo) {
              // Restore to the variation node
              int varStep = subTreeStepMap.get(subTreeDepth);
              for (int s = 0; s < varStep; s++) {
                history.previous();
              }
            }
            subTreeDepth -= 1;
          } else {
            // Allow the comment tag includes '('
            tagContentBuilder.append(c);
          }
          break;
        case '[':
          if (!inProp) {
            inProp = true;
            if (subTreeDepth > 1 && !isMultiGo) {
              break;
            }
            inTag = true;
            String tagTemp = tagBuilder.toString();
            if (!tagTemp.isEmpty()) {
              // Ignore small letters in tags for the long format Smart-Go file.
              // (ex) "PlayerBlack" ==> "PB"
              // It is the default format of mgt, an old SGF tool.
              // (Mgt is still supported in Debian and Ubuntu.)
              tag = tagTemp.replaceAll("[a-z]", "");
            }
            tagContentBuilder = new StringBuilder();
          } else {
            tagContentBuilder.append(c);
          }
          break;
        case ']':
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          inTag = false;
          inProp = false;
          tagBuilder = new StringBuilder();
          String tagContent = tagContentBuilder.toString();
          // We got tag, we can parse this tag now.
          if (tag.equals("B") || tag.equals("W")) {
            moveStart = true;
            addPassForMove = !currentNodeHasSetupSnapshot;
            int[] move = convertSgfPosToCoord(tagContent);
            // Save the step count
            subTreeStepMap.put(subTreeDepth, subTreeStepMap.get(subTreeDepth) + 1);
            Stone color = tag.equals("B") ? Stone.BLACK : Stone.WHITE;
            boolean newBranch = (subTreeStepMap.get(subTreeDepth) == 1);
            if (move == null) {
              history.pass(color, newBranch, false);
            } else {
              history.place(move[0], move[1], color, newBranch);
            }
            currentNodeHasMove = true;
            if (newBranch) {
              processPendingPros(history, pendingProps);
            }
          } else if (tag.equals("C")) {
            // Support comment
            boolean rootSetupContext =
                isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
            if (rootSetupContext) {
              headComment = tagContent;
            } else if (shouldBufferLeadingSetupMetadata(
                currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
              bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
            } else {
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              target.getData().comment = tagContent;
            }
          } else if (tag.equals("AB") || tag.equals("AW")) {
            int[] move = convertSgfPosToCoord(tagContent);
            Stone color = tag.equals("AB") ? Stone.BLACK : Stone.WHITE;
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootSetupProperty(history, tag, tagContent, move, color);
            } else {
              if (addPassForMove) {
                boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
              addNodePropertyOnNode(history, target, tag, tagContent);
              if (move != null) {
                addExtraStoneOnNode(history, target, move[0], move[1], color);
              }
            }
          } else if (tag.equals("PL")) {
            if (isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode)) {
              applyRootPlayerProperty(history, tagContent);
            } else {
              if (addPassForMove) {
                boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                addPassForMove = false;
                currentNodeHasSetupSnapshot = true;
              }
              BoardHistoryNode target =
                  getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
              movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
              applyCurrentPlayerPropertyOnNode(history, target, tagContent);
            }
          } else if (tag.equals("PB")) {
          } else if (tag.equals("PW")) {
          } else if (tag.equals("KM") && Lizzie.config.readKomi) {
            try {
              if (!tagContent.trim().isEmpty()) {
                Double komi = Double.parseDouble(tagContent);
                if (komi >= 200) {
                  komi = komi / 100;
                  if (komi <= 4 && komi >= -4) komi = komi * 2;
                }
                if (komi.toString().endsWith(".75") || komi.toString().endsWith(".25"))
                  komi = komi * 2;
                if (Math.abs(komi) < Board.boardWidth * Board.boardHeight) {
                  history.getGameInfo().setKomi(komi);
                  history.getGameInfo().changeKomi();
                  Lizzie.leelaz.komi(komi);
                }
              }
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          } else {
            if (moveStart) {
              // Other SGF node properties
              if ("AE".equals(tag)) {
                // remove a stone
                if (addPassForMove) {
                  boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                  appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY);
                }
              } else if (currentNodeHasSetupSnapshot) {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, false, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else {
                boolean firstProp = (subTreeStepMap.get(subTreeDepth) == 0);
                if (firstProp) {
                  addProperty(pendingProps, tag, tagContent);
                } else {
                  history.addNodeProperty(tag, tagContent);
                }
              }
            } else {
              boolean rootSetupContext =
                  isRootSetupContext(history, moveStart, allowRootSetupOnCurrentNode);
              if ("AE".equals(tag) && rootSetupContext) {
                int[] move = convertSgfPosToCoord(tagContent);
                applyRootSetupProperty(history, tag, tagContent, move, Stone.EMPTY);
              } else if (rootSetupContext) {
                if ("N".equals(tag) && headComment.isEmpty()) headComment = tagContent;
                else addProperty(gameProperties, tag, tagContent);
              } else if ("AE".equals(tag)) {
                if (addPassForMove) {
                  boolean newBranch = (subTreeStepMap.get(subTreeDepth) <= 1);
                  appendSetupSnapshot(history, newBranch, pendingProps, currentNodeHasMove);
                  addPassForMove = false;
                  currentNodeHasSetupSnapshot = true;
                }
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                movePendingSetupMetadataToTarget(history, target, pendingSetupMetadata);
                addNodePropertyOnNode(history, target, tag, tagContent);
                int[] move = convertSgfPosToCoord(tagContent);
                if (move != null) {
                  removeStoneOnNode(history, target, move[0], move[1], Stone.EMPTY);
                }
              } else if (shouldBufferLeadingSetupMetadata(
                  currentNodeHasMove, currentNodeHasSetupSnapshot, rootSetupContext, tag)) {
                bufferLeadingSetupMetadata(pendingSetupMetadata, tag, tagContent);
              } else {
                BoardHistoryNode target =
                    getSetupPropertyTargetNode(history, currentNodeHasSetupSnapshot);
                addNodePropertyOnNode(history, target, tag, tagContent);
              }
            }
          }
          break;
        case ';':
          if (inProp) {
            // support C[a;b;c;]
            tagContentBuilder.append(c);
          } else if (!(subTreeDepth > 1 && !isMultiGo)) {
            flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
            addPassForMove = true;
            currentNodeHasSetupSnapshot = false;
            currentNodeHasMove = false;
          }
          break;
        default:
          if (subTreeDepth > 1 && !isMultiGo) {
            break;
          }
          if (inTag) {
            if (c == '\\') {
              escaping = true;
              if (!tag.equals("C")) tagContentBuilder.append(c);
              continue;
            }
            tagContentBuilder.append(c);
          } else {
            if (c != '\n' && c != '\r' && c != '\t' && c != ' ') {
              tagBuilder.append(c);
            }
          }
      }
    }
    flushPendingSetupMetadataToCurrentNode(history, pendingSetupMetadata);
    history.toBranchTop();
    return history.getCurrentHistoryNode().numberOfChildren() - 1;
  }

  private static String asCoord(int i) {
    int[] cor = Board.getCoord(i);

    return asCoord(cor);
  }

  private static boolean isAnalysisTag(String tag) {
    return "LZ".equals(tag) || "LZOP".equals(tag) || "LZ2".equals(tag) || "LZOP2".equals(tag);
  }

  private static boolean isSecondaryEngineAnalysisTag(String tag) {
    return "LZ2".equals(tag) || "LZOP2".equals(tag);
  }

  private static void applyAnalysisTagOnDetachedHistory(
      BoardHistoryList history, String tag, String tagContent) {
    if (history == null) {
      return;
    }
    applyAnalysisTagOnDetachedHistory(history.getData(), tag, tagContent);
  }

  private static void applyAnalysisTagOnDetachedHistory(
      BoardData target, String tag, String tagContent) {
    if (target == null || Utils.isBlank(tagContent)) {
      return;
    }
    String[] lines = tagContent.split("\n", 2);
    String[] line1 = lines[0].trim().split("\\s+");
    if (line1.length < 3 || Utils.isBlank(line1[0])) {
      return;
    }
    String line2 = lines.length > 1 ? lines[1] : "";
    boolean secondaryEngine = isSecondaryEngineAnalysisTag(tag);
    applyAnalysisHeader(target, line1, secondaryEngine);
    int numPlayouts = parseAnalysisPlayouts(line1[2]);
    if (numPlayouts <= 0 || Utils.isBlank(line2)) {
      return;
    }
    double stdev = extractAnalysisScoreStdev(line1);
    ParsedAnalysisInfo parsed = parseAnalysisInfoForDetachedNode(line2, stdev, secondaryEngine);
    applyParsedAnalysisInfo(target, parsed, secondaryEngine);
  }

  private static void applyAnalysisHeader(
      BoardData target, String[] line1, boolean secondaryEngineTag) {
    int headerPlayouts = parseAnalysisPlayouts(line1[2]);
    int headerSlots = line1.length;
    if (secondaryEngineTag) {
      target.engineName2 = line1[0];
      target.winrate2 = 100 - parseDoubleOrDefault(line1[1], 50);
      target.setPlayouts2(headerPlayouts);
      target.isKataData2 = false;
      target.analysisHeaderSlots2 = headerSlots;
      target.scoreMean2 = 0;
      target.scoreStdev2 = 0;
      target.pda2 = 0;
      if (line1.length >= 4) {
        target.scoreMean2 = parseDoubleOrDefault(line1[3], 0);
        target.isKataData2 = true;
      }
      if (line1.length >= 5) {
        target.scoreStdev2 = parseDoubleOrDefault(line1[4], 0);
      }
      if (line1.length >= 6) {
        target.pda2 = parseDoubleOrDefault(line1[5], 0);
      }
      return;
    }
    target.engineName = line1[0];
    target.winrate = 100 - parseDoubleOrDefault(line1[1], 50);
    target.setPlayouts(headerPlayouts);
    target.isKataData = false;
    target.analysisHeaderSlots = headerSlots;
    target.scoreMean = 0;
    target.scoreStdev = 0;
    target.pda = 0;
    if (line1.length >= 4) {
      target.scoreMean = parseDoubleOrDefault(line1[3], 0);
      target.isKataData = true;
    }
    if (line1.length >= 5) {
      target.scoreStdev = parseDoubleOrDefault(line1[4], 0);
    }
    if (line1.length >= 6) {
      target.pda = parseDoubleOrDefault(line1[5], 0);
    }
  }

  private static int parseAnalysisPlayouts(String rawValue) {
    if (Utils.isBlank(rawValue)) {
      return 0;
    }
    String normalized = rawValue.trim().toLowerCase(Locale.ENGLISH).replace(",", "");
    double multiplier = 1;
    if (normalized.endsWith("m")) {
      multiplier = 1_000_000;
      normalized = normalized.substring(0, normalized.length() - 1);
    } else if (normalized.endsWith("k")) {
      multiplier = 1_000;
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    try {
      double value = Double.parseDouble(normalized.replaceAll("[^0-9.\\-]", ""));
      return value <= 0 ? 0 : (int) Math.round(value * multiplier);
    } catch (NumberFormatException ex) {
      String digits = normalized.replaceAll("[^0-9]", "");
      if (digits.isEmpty()) {
        return 0;
      }
      try {
        return Integer.parseInt(digits);
      } catch (NumberFormatException nestedEx) {
        return 0;
      }
    }
  }

  private static double extractAnalysisScoreStdev(String[] line1) {
    if (line1.length < 5) {
      return 0;
    }
    return parseDoubleOrDefault(line1[4], 0);
  }

  private static double parseDoubleOrDefault(String rawValue, double defaultValue) {
    try {
      return Double.parseDouble(rawValue);
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static ParsedAnalysisInfo parseAnalysisInfoForDetachedNode(
      String line, double stdev, boolean secondaryEngineTag) {
    boolean hasOwnership = line.contains("ownership");
    String[] lineInfo = hasOwnership ? line.split("ownership", 2) : null;
    String analysisLine = hasOwnership ? lineInfo[0] : line;
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = analysisLine.split(" info ");
    int maxSuggestions =
        secondaryEngineTag
            ? 361
            : (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
                ? Lizzie.config.limitMaxSuggestion
                : 361);
    for (String variation : variations) {
      if (Utils.isBlank(variation) || maxSuggestions < 1) {
        continue;
      }
      bestMoves.add(parseMoveDataFromAnalysis(variation, bestMoves));
      maxSuggestions -= 1;
    }
    if (!bestMoves.isEmpty()) {
      bestMoves.get(0).scoreStdev = stdev;
    }
    return new ParsedAnalysisInfo(bestMoves, parseOwnershipValues(lineInfo));
  }

  private static MoveData parseMoveDataFromAnalysis(String variation, List<MoveData> bestMoves) {
    MoveData result = new MoveData();
    String[] data = variation.trim().split("\\s+");
    for (int i = 0; i < data.length - 1; i++) {
      String key = data[i];
      if ("pv".equals(key)) {
        captureVariationTokens(result, data, i);
        break;
      }
      String value = data[++i];
      applyMoveDataToken(result, key, value);
    }
    result.order = bestMoves.size();
    if ((result.variation == null || result.variation.isEmpty())
        && !Utils.isBlank(result.coordinate)) {
      result.variation = new ArrayList<>();
      result.variation.add(result.coordinate);
    }
    return result;
  }

  private static void captureVariationTokens(MoveData target, String[] data, int pvIndex) {
    int pvVisitsIndex = -1;
    for (int cursor = pvIndex + 1; cursor < data.length; cursor++) {
      if ("pvVisits".equals(data[cursor])) {
        pvVisitsIndex = cursor;
        target.pvVisits = new ArrayList<>(asList(data).subList(cursor + 1, data.length));
        break;
      }
    }
    int end = pvVisitsIndex > -1 ? pvVisitsIndex : data.length;
    int maxLength = end - pvIndex - 1;
    if (Lizzie.config.limitBranchLength > 0 && maxLength > Lizzie.config.limitBranchLength) {
      end = pvIndex + 1 + Lizzie.config.limitBranchLength;
    }
    target.variation = new ArrayList<>(asList(data).subList(pvIndex + 1, end));
  }

  private static void applyMoveDataToken(MoveData target, String key, String value) {
    if ("move".equals(key)) {
      target.coordinate = value;
    } else if ("visits".equals(key)) {
      target.playouts = parseAnalysisPlayouts(value);
    } else if ("winrate".equals(key)) {
      target.winrate = parseAnalysisPlayouts(value) / 100.0;
      target.lcb = target.winrate;
    } else if ("prior".equals(key)) {
      target.policy = parsePriorValue(value);
    } else if ("scoreMean".equals(key)) {
      target.scoreMean = parseDoubleOrDefault(value, 0);
      target.isKataData = true;
    } else if ("scoreStdev".equals(key)) {
      target.scoreStdev = parseDoubleOrDefault(value, 0);
      target.isKataData = true;
    }
  }

  private static double parsePriorValue(String rawValue) {
    try {
      return Integer.parseInt(rawValue) / 100.0;
    } catch (NumberFormatException ex) {
      return parseDoubleOrDefault(rawValue, 0);
    }
  }

  private static ArrayList<Double> parseOwnershipValues(String[] lineInfo) {
    if (lineInfo == null || lineInfo.length < 2) {
      return null;
    }
    ArrayList<Double> estimateArray = new ArrayList<>();
    String[] params = lineInfo[1].trim().split("\\s+");
    for (String param : params) {
      try {
        estimateArray.add(Double.parseDouble(param));
      } catch (NumberFormatException ex) {
        break;
      }
    }
    return estimateArray;
  }

  private static void applyParsedAnalysisInfo(
      BoardData target, ParsedAnalysisInfo parsed, boolean secondaryEngineTag) {
    if (parsed.bestMoves.isEmpty()) {
      return;
    }
    MoveData bestMove = parsed.bestMoves.get(0);
    if (secondaryEngineTag) {
      boolean headerHasKata = target.isKataData2;
      target.bestMoves2 = parsed.bestMoves;
      target.estimateArray2 = parsed.estimateArray;
      target.isSaiData2 = target.isSaiData2 || bestMove.isSaiData;
      target.isKataData2 = target.isKataData2 || bestMove.isKataData;
      if (!headerHasKata && bestMove.isKataData) {
        if (shouldApplyBestMoveScore(target.scoreMean2, bestMove.scoreMean)) {
          target.scoreMean2 = bestMove.scoreMean;
        }
        target.scoreStdev2 = bestMove.scoreStdev;
      }
      return;
    }
    boolean headerHasKata = target.isKataData;
    target.bestMoves = parsed.bestMoves;
    target.estimateArray = parsed.estimateArray;
    target.playoutsChanged = true;
    target.isSaiData = target.isSaiData || bestMove.isSaiData;
    target.isKataData = target.isKataData || bestMove.isKataData;
    if (!headerHasKata && bestMove.isKataData) {
      if (shouldApplyBestMoveScore(target.scoreMean, bestMove.scoreMean)) {
        target.scoreMean = bestMove.scoreMean;
      }
      target.scoreStdev = bestMove.scoreStdev;
    }
  }

  private static boolean shouldApplyBestMoveScore(
      double headerScoreMean, double bestMoveScoreMean) {
    return Double.compare(bestMoveScoreMean, 0) != 0 || Double.compare(headerScoreMean, 0) == 0;
  }

  private static final class ParsedAnalysisInfo {
    private final List<MoveData> bestMoves;
    private final ArrayList<Double> estimateArray;

    private ParsedAnalysisInfo(List<MoveData> bestMoves, ArrayList<Double> estimateArray) {
      this.bestMoves = bestMoves;
      this.estimateArray = estimateArray;
    }
  }

  private static List<MoveData> parseInfofromfile(String line, double stdev) {
    boolean hasOwnership = false;
    String[] lineInfo = null;
    if (line.contains("ownership")) {
      hasOwnership = true;
      lineInfo = line.split("ownership");
      line = lineInfo[0];
    }
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    int k =
        (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
            ? Lizzie.config.limitMaxSuggestion
            : 361);
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfofromfile(var, bestMoves));
        k = k - 1;
        if (k < 1) break;
      }
    }
    if (bestMoves.size() > 0) bestMoves.get(0).scoreStdev = stdev;
    ArrayList<Double> estimateArray = new ArrayList<Double>();
    if (hasOwnership && lineInfo != null && lineInfo.length > 1) {
      String[] params2 = lineInfo[1].trim().split(" ");
      for (int i = 0; i < params2.length; i++) {
        try {
          estimateArray.add(Double.parseDouble(params2[i]));
        } catch (NumberFormatException e) {
          break;
        }
      }
    } else estimateArray = null;
    Lizzie.board
        .getData()
        .tryToSetBestMoves(
            bestMoves,
            Lizzie.board.getData().engineName,
            false,
            MoveData.getPlayouts(bestMoves),
            estimateArray);

    return bestMoves;
  }

  private static List<MoveData> parseInfofromfile2(String line, double stdev) {
    boolean hasOwnership = false;
    String[] lineInfo = null;
    if (line.contains("ownership")) {
      hasOwnership = true;
      lineInfo = line.split("ownership");
      line = lineInfo[0];
    }
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    // int k =
    // Lizzie.config.config.getJSONObject("leelaz").getInt("max-suggestion-moves");
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfofromfile(var, bestMoves));
        // k = k - 1;
        // if (k < 1) break;
      }
    }
    if (bestMoves.size() > 0) bestMoves.get(0).scoreStdev = stdev;
    ArrayList<Double> estimateArray = new ArrayList<Double>();
    if (hasOwnership && lineInfo != null && lineInfo.length > 1) {
      String[] params2 = lineInfo[1].trim().split(" ");
      for (int i = 0; i < params2.length; i++) {
        try {
          estimateArray.add(Double.parseDouble(params2[i]));
        } catch (NumberFormatException e) {
          break;
        }
      }
    } else estimateArray = null;
    Lizzie.board
        .getData()
        .tryToSetBestMoves2(
            bestMoves,
            Lizzie.board.getData().engineName2,
            false,
            MoveData.getPlayouts(bestMoves),
            estimateArray);
    return bestMoves;
  }

  public static String asCoord(int[] c) {
    if (Board.boardHeight >= 52 || Board.boardWidth >= 52) {
      return c[0] + "_" + c[1];
    } else {
      char x = alphabet.charAt(c[0]);
      char y = alphabet.charAt(c[1]);

      return String.format(Locale.ENGLISH, "%c%c", x, y);
    }
  }
}

class BlunderMoves implements Comparable<BlunderMoves> {

  public int moveNumber;
  public Double diffWinrate;

  public BlunderMoves(int moveNumber, Double diffWinrate) {
    this.moveNumber = moveNumber;
    this.diffWinrate = diffWinrate;
  }

  @Override
  public String toString() {
    return moveNumber
        + "("
        + (diffWinrate > 0 ? "+" : "-")
        + String.format(Locale.ENGLISH, "%.1f", Math.abs(diffWinrate))
        + "%)";
  }

  @Override
  public int compareTo(BlunderMoves move) {
    if (Math.abs(move.diffWinrate) > Math.abs(this.diffWinrate)) return 1;
    else return -1;
  }
}
