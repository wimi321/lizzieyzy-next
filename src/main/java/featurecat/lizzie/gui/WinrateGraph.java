package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class WinrateGraph {

  private static class QuickOverviewMove {
    final BoardHistoryNode node;
    final int moveNumber;
    final String moveName;
    final double winrate;
    final double swing;
    final boolean hasAnalysis;
    final boolean connectsToPrevious;

    QuickOverviewMove(
        BoardHistoryNode node,
        int moveNumber,
        String moveName,
        double winrate,
        double swing,
        boolean hasAnalysis,
        boolean connectsToPrevious) {
      this.node = node;
      this.moveNumber = moveNumber;
      this.moveName = moveName;
      this.winrate = winrate;
      this.swing = swing;
      this.hasAnalysis = hasAnalysis;
      this.connectsToPrevious = connectsToPrevious;
    }
  }

  private static class QuickOverviewPoint {
    final QuickOverviewMove move;
    final int x;
    final int y;

    QuickOverviewPoint(QuickOverviewMove move, int x, int y) {
      this.move = move;
      this.x = x;
      this.y = y;
    }
  }

  private static class QuickOverviewLayout {
    final List<QuickOverviewPoint> points;
    final int overviewX;
    final int overviewY;
    final int overviewWidth;
    final int overviewHeight;
    final int innerX;
    final int innerY;
    final int innerWidth;
    final int innerHeight;
    final int dotSize;
    final boolean[][] dotMask;
    final int barWidth;
    final double issueThreshold;
    final double swingScale;

    QuickOverviewLayout(
        List<QuickOverviewPoint> points,
        int overviewX,
        int overviewY,
        int overviewWidth,
        int overviewHeight,
        int innerX,
        int innerY,
        int innerWidth,
        int innerHeight,
        int dotSize,
        boolean[][] dotMask,
        int barWidth,
        double issueThreshold,
        double swingScale) {
      this.points = points;
      this.overviewX = overviewX;
      this.overviewY = overviewY;
      this.overviewWidth = overviewWidth;
      this.overviewHeight = overviewHeight;
      this.innerX = innerX;
      this.innerY = innerY;
      this.innerWidth = innerWidth;
      this.innerHeight = innerHeight;
      this.dotSize = dotSize;
      this.dotMask = dotMask;
      this.barWidth = barWidth;
      this.issueThreshold = issueThreshold;
      this.swingScale = swingScale;
    }
  }

  private static class GraphPoint {
    final BoardHistoryNode node;
    final int x;
    final int y;

    GraphPoint(BoardHistoryNode node, int x, int y) {
      this.node = node;
      this.x = x;
      this.y = y;
    }
  }

  private int DOT_RADIUS = 3;
  private static final int GRAPH_ANCHOR_HIT_HALF_SIZE = 2;
  private int[] origParams = {0, 0, 0, 0};
  private int[] params = {0, 0, 0, 0, 0};
  public BoardHistoryNode mouseOverNode;
  // private int numMovesOfPlayed = 0;
  public int mode = 0;
  private double maxScoreLead = Lizzie.config.initialMaxScoreLead;
  private double weightedMaxScoreBlunder = 50;
  private boolean largeEnough = false;
  private BoardHistoryNode forkNode = null;
  private int scoreAjustMove = -10;
  private boolean scoreAjustBelow;
  private Color whiteColor = new Color(240, 240, 240);
  private boolean noC = false;
  private List<GraphPoint> renderedGraphPoints = Collections.emptyList();
  private QuickOverviewLayout renderedQuickOverviewLayout;
  private int[] renderedOrigParams = {0, 0, 0, 0};
  private int[] renderedParams = {0, 0, 0, 0, 0};
  private BoardHistoryNode renderedCurrentGraphNode;
  private BoardHistoryNode renderedGraphEndNode;
  private BoardHistoryNode renderedMainEndNode;
  private int renderedMode;
  private boolean renderedEngineGame;
  private boolean renderedPkBoard;
  private boolean renderedShowWinrateLine;
  private boolean renderedFrameInPlayMode;
  private final Map<Integer, boolean[][]> quickOverviewDotMaskCache = new HashMap<>();

  private int clampDotY(int dotY, int dotRadius) {
    return Math.max(origParams[1], Math.min(origParams[1] + origParams[3] - dotRadius * 2, dotY));
  }

  private Color getBlunderColor(double winrateDrop, double scoreDrop) {
    if (scoreDrop > 5.0 || winrateDrop > 20.0) return new Color(235, 60, 60, 220);
    if (scoreDrop > 2.0 || winrateDrop > 10.0) return new Color(230, 140, 40, 220);
    if (scoreDrop > 1.0 || winrateDrop > 5.0) return new Color(230, 200, 50, 200);
    if (scoreDrop > 0.3 || winrateDrop > 2.0) return new Color(150, 180, 80, 150);
    return new Color(120, 120, 120, 50);
  }

  public void draw(
      Graphics2D g,
      Graphics2D gBlunder,
      Graphics2D gBackground,
      int posx,
      int posy,
      int width,
      int height) {
    largeEnough = width > 475 && height > 335;
    clearRenderedPointSources();
    BoardHistoryNode curMove = Lizzie.board.getHistory().getCurrentHistoryNode();
    BoardHistoryNode node = curMove;
    // draw background rectangle
    final Paint customBackground =
        Lizzie.config.isAppleStyle ? new Color(22, 25, 30, 230) : new Color(24, 28, 32, 235);
    gBackground.setPaint(customBackground);
    gBackground.fillRect(posx, posy, width, height);

    int strokeRadius = 1;
    // record parameters (before resizing) for calculating moveNumber
    origParams[0] = posx;
    origParams[1] = posy;
    origParams[2] = width;
    origParams[3] = height;
    int blunderBottom = posy + height;

    // resize the box now so it's inside the border
    posy += 2 * strokeRadius;
    width -= 6 * strokeRadius;
    height -= 4 * strokeRadius;

    // draw lines marking 50% 60% 70% etc.
    Stroke dashed =
        new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {4}, 0);
    gBackground.setStroke(dashed);

    gBackground.setColor(new Color(255, 255, 255, 30));
    int winRateGridLines = Lizzie.frame.winRateGridLines;
    for (int i = 1; i <= winRateGridLines; i++) {
      double percent = i * 100.0 / (winRateGridLines + 1);
      int y = posy + height - (int) (height * percent / 100);
      gBackground.drawLine(posx, y, posx + width, y);
    }
    if (Lizzie.frame.isInPlayMode()) return;
    //    if(Lizzie.frame.extraMode==8)
    //    	{if(width>65)width=width-12;
    //    	else width=width*85/100;}
    g.setColor(Lizzie.config.winrateLineColor);
    // g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));

    Optional<BoardHistoryNode> topOfVariation = Optional.empty();
    int numMoves = 0;
    if (!curMove.isMainTrunk()) {
      // We're in a variation, need to draw both main trunk and variation
      // Find top of variation
      BoardHistoryNode top = curMove.findTop();
      topOfVariation = Optional.of(top);
      // Find depth of main trunk, need this for plot scaling
      numMoves = top.getDepth() + top.getData().moveNumber - 1;
      //   g.setStroke(dashed);
    }

    // Go to end of variation and work our way backwards to the root

    while (node.next().isPresent()) {
      node = node.next().get();
    }
    if (numMoves < node.getData().moveNumber - 1) {
      numMoves = node.getData().moveNumber - 1;
    }

    if (numMoves < 1) return;
    if (numMoves < 50) numMoves = 50;

    // Plot
    width = (int) (width * 0.98); // Leave some space after last move
    double lastWr = 50;
    double lastScore = 0;
    boolean lastNodeOk = false;
    int movenum = node.getData().moveNumber - 1;
    int lastOkMove = -1;
    //    if (Lizzie.config.dynamicWinrateGraphWidth && this.numMovesOfPlayed > 0) {
    //      numMoves = this.numMovesOfPlayed;
    //    }
    if (width >= 150) {
      gBackground.setFont(
          new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11));
      gBackground.setColor(new Color(200, 200, 200));
      if (numMoves <= 63) {
        for (int i = 1; i <= (numMoves / 10); i++)
          if (numMoves - i * 10 > 3)
            gBackground.drawString(
                String.valueOf(i * 10),
                posx + (i * 10 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else if (numMoves <= 125) {
        for (int i = 1; i <= (numMoves / 20); i++)
          if (numMoves - i * 20 > 3)
            gBackground.drawString(
                String.valueOf(i * 20),
                posx + (i * 20 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else if (numMoves < 205) {
        for (int i = 1; i <= (numMoves / 30); i++)
          if (numMoves - i * 30 > 3)
            gBackground.drawString(
                String.valueOf(i * 30),
                posx + (i * 30 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else {
        for (int i = 1; i <= (numMoves / 40); i++)
          if (numMoves - i * 40 > 3)
            gBackground.drawString(
                String.valueOf(i * 40),
                posx + (i * 40 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      }
    }
    double cwr = -1;
    int cmovenum = -1;
    double mwr = -1;
    int mmovenum = -1;
    int curScoreMoveNum = -1;
    double drawCurSoreMean = 0;
    int mScoreMoveNum = -1;
    double drawmSoreMean = 0;
    if (EngineManager.isEngineGame || Lizzie.board.isPkBoard) {
      int saveCurMovenum = 0;
      double saveCurWr = 0;
      if (numMoves < 2) return;
      Stroke previousStroke = g.getStroke();
      int x =
          posx
              + ((Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber - 1)
                  * width
                  / numMoves);
      g.setStroke(new BasicStroke(2));
      g.setColor(new Color(120, 220, 255, 200));
      // if (Lizzie.board.getHistory().getCurrentHistoryNode() !=
      // Lizzie.board.getHistory().getEnd())
      g.drawLine(x, posy, x, posy + height);
      g.setStroke(previousStroke);
      String moveNumString =
          String.valueOf(Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber);
      //  int mw = g.getFontMetrics().stringWidth(moveNumString);
      int margin = strokeRadius;
      //      int mx = x - posx < width / 2 ? x + margin : x - mw - margin;
      //      if (node.getData().blackToPlay) {
      //
      //      } else {
      //        g.setColor(Color.BLACK);
      //      }
      g.setColor(Color.WHITE);
      if (Lizzie.board.getHistory().getCurrentHistoryNode() != Lizzie.board.getHistory().getEnd()) {
        Font f =
            new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(12) : 12);
        g.setFont(f);
        g.setColor(Color.WHITE);
        int moveNum = Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber;
        if (moveNum < 10)
          g.drawString(
              moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 10, posy + height - margin);
        else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
          g.drawString(
              moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 22, posy + height - margin);
        else
          g.drawString(
              moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 16, posy + height - margin);
      }
      while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
        BoardHistoryNode twoBackNode = node.previous().get().previous().get();
        int currentMoveIndex = node.getData().moveNumber - 1;
        int twoBackMoveIndex = Math.max(0, twoBackNode.getData().moveNumber - 1);
        double wr = 50;
        double score = 0;
        if (node.getData().getPlayouts() > 0) {
          wr = node.getData().winrate;
          score = node.getData().scoreMean;
        } else if (twoBackNode.getData().getPlayouts() > 0) {
          wr = twoBackNode.getData().winrate;
          score = twoBackNode.getData().scoreMean;
        }
        if (twoBackNode.getData().getPlayouts() > 0) {
          lastWr = twoBackNode.getData().winrate;
          lastScore = twoBackNode.getData().scoreMean;
        } else {
          lastWr = wr;
          lastScore = score;
        }
        if (Lizzie.config.showBlunderBar) {
          double lastMoveRate = Math.abs(lastWr - wr);
          double lastMoveScoreRate = Math.abs(lastScore - score);
          gBlunder.setColor(getBlunderColor(lastMoveRate, lastMoveScoreRate));
          int lastHeight = Math.min(15, Math.max(6, height / 12));
          int leftIndex = Math.min(twoBackMoveIndex, currentMoveIndex);
          int rightIndex = Math.max(twoBackMoveIndex, currentMoveIndex);
          int rectStart = posx + leftIndex * width / numMoves;
          int rectEnd = posx + rightIndex * width / numMoves;
          int rectWidth = Math.max(Lizzie.config.minimumBlunderBarWidth, rectEnd - rectStart);
          gBlunder.fillRect(rectStart, blunderBottom - lastHeight, rectWidth + 1, lastHeight);
        }

        lastOkMove = twoBackMoveIndex;
        if (Lizzie.config.showWinrateLine) {
          if (node.getData().blackToPlay) {
            g.setColor(new Color(100, 180, 255));
            g.drawLine(
                posx + (twoBackMoveIndex * width / numMoves),
                posy + height - (int) (lastWr * height / 100),
                posx + (currentMoveIndex * width / numMoves),
                posy + height - (int) (wr * height / 100));

          } else {
            g.setColor(whiteColor);
            g.drawLine(
                posx + (twoBackMoveIndex * width / numMoves),
                posy + height - (int) (lastWr * height / 100),
                posx + (currentMoveIndex * width / numMoves),
                posy + height - (int) (wr * height / 100));
          }
          if (curMove.previous().isPresent() && currentMoveIndex > 1) {
            if (node == curMove) {
              saveCurMovenum = currentMoveIndex;
              saveCurWr = wr;
            } else if (node == curMove.previous().get()) {
              if (node.getData().blackToPlay) {
                g.setColor(new Color(100, 180, 255));
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(f);
                String wrString = String.format(Locale.ENGLISH, "%.1f", wr);
                int stringWidth = g.getFontMetrics().stringWidth(wrString);
                int xPos = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                xPos = Math.max(xPos, origParams[0]);
                xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
                if (wr > 50) {
                  if (wr > 90) {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  }
                } else {
                  if (wr < 10) {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  }
                }
              } else {
                g.setColor(whiteColor);
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(f);
                g.setColor(Color.WHITE);
                String wrString = String.format(Locale.ENGLISH, "%.1f", wr);
                int stringWidth = g.getFontMetrics().stringWidth(wrString);
                int xPos = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                xPos = Math.max(xPos, origParams[0]);
                xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
                if (wr > 50) {
                  if (wr < 90) {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  }
                } else {
                  if (wr < 10) {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  }
                }
              }
            }
          }
        }
        node = node.previous().get();
      }
      if (saveCurMovenum > 1) {
        String wrString = String.format(Locale.ENGLISH, "%.1f", saveCurWr);
        int stringWidth = g.getFontMetrics().stringWidth(wrString);
        int xPos = posx + (saveCurMovenum * width / numMoves) - stringWidth / 2;
        xPos = Math.max(xPos, origParams[0]);
        xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
        if (curMove.getData().blackToPlay) {
          g.setColor(new Color(100, 180, 255));
          g.fillOval(
              posx + (saveCurMovenum * width / numMoves) - DOT_RADIUS,
              clampDotY(posy + height - (int) (saveCurWr * height / 100) - DOT_RADIUS, DOT_RADIUS),
              DOT_RADIUS * 2,
              DOT_RADIUS * 2);
          Font f =
              new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
          g.setFont(f);
          if (saveCurWr > 50) {
            if (saveCurWr > 90) {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS);
            } else {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS);
            }
          } else {
            if (saveCurWr < 10) {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS);
            } else {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS);
            }
          }
        } else {
          g.setColor(whiteColor);
          g.fillOval(
              posx + (saveCurMovenum * width / numMoves) - DOT_RADIUS,
              clampDotY(posy + height - (int) (saveCurWr * height / 100) - DOT_RADIUS, DOT_RADIUS),
              DOT_RADIUS * 2,
              DOT_RADIUS * 2);
          Font f =
              new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
          g.setFont(f);
          g.setColor(Color.WHITE);
          if (saveCurWr > 50) {
            if (saveCurWr < 90) {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS);
            } else {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS);
            }
          } else {
            if (saveCurWr < 10) {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS);
            } else {
              g.drawString(
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS);
            }
          }
        }
      }
    } else {
      if (mode == 0) {
        boolean canDrawBlunderBar = true;
        while (node.previous().isPresent()) {
          double wr = node.getData().winrate;
          double score = node.getData().scoreMean;
          int playouts = node.getData().getPlayouts();
          if (playouts > 0) {
            if (wr < 0) {
              wr = 100 - lastWr;
              score = lastScore;
            } else if (!node.getData().blackToPlay) {
              wr = 100 - wr;
              score = -score;
            }
            if (node == curMove) {
              // Draw a vertical line at the current move
              Stroke previousStroke = g.getStroke();
              int x = posx + (movenum * width / numMoves);
              g.setStroke(new BasicStroke(2));
              g.setColor(new Color(120, 220, 255, 200));
              g.drawLine(x, posy, x, posy + height);
              // Show move number
              String moveNumString = String.valueOf(node.getData().moveNumber);
              //    int mw = g.getFontMetrics().stringWidth(moveNumString);
              int margin = strokeRadius;
              // int mx = x - posx < width / 2 ? x + margin : x - mw - margin;
              if (!noC) {
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(12) : 12);
                g.setFont(f);
                g.setColor(Color.WHITE);
                int moveNum = node.getData().moveNumber;
                if (wr < 3) {
                  int fontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
                  if (moveNum < 10)
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 10,
                        posy + fontHeight - margin);
                  else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber
                      > 99)
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 22,
                        posy + fontHeight - margin);
                  else
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 16,
                        posy + fontHeight - margin);
                } else {
                  if (moveNum < 10)
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 10,
                        posy + height - margin);
                  else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber
                      > 99)
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 22,
                        posy + height - margin);
                  else
                    g.drawString(
                        moveNumString,
                        moveNum < numMoves / 2 ? x + 3 : x - 16,
                        posy + height - margin);
                }
              }
              g.setStroke(previousStroke);
            }

            // if (Lizzie.frame.isPlayingAgainstLeelaz
            // && Lizzie.frame.playerIsBlack == !node.getData().blackToPlay) {
            // wr = lastWr;
            // }

            if (lastNodeOk) g.setColor(new Color(100, 180, 255));
            // g.setColor(Color.BLACK);
            else g.setColor(Lizzie.config.winrateMissLineColor);

            if (lastOkMove > 0 && lastOkMove - movenum < 25) {
              if (Lizzie.config.showWinrateLine) {
                g.drawLine(
                    posx + (lastOkMove * width / numMoves),
                    posy + height - (int) (lastWr * height / 100),
                    posx + (movenum * width / numMoves),
                    posy + height - (int) (wr * height / 100));
              }
            }
            if (forkNode != null && forkNode == node) {
              canDrawBlunderBar = true;
              g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
            }
            lastWr = wr;
            lastScore = score;
            lastNodeOk = true;
            // Check if we were in a variation and has reached the main trunk
            if (topOfVariation.isPresent()
                && topOfVariation.get() == node
                && node.next().isPresent()) {
              // Reached top of variation, go to end of main trunk before continuing
              canDrawBlunderBar = false;
              forkNode = topOfVariation.get();
              g.setStroke(dashed);
              node = graphTraversalEnd(node);
              movenum = node.getData().moveNumber - 1;
              Double continuationWinrate = displayedGraphWinrate(node, lastWr);
              if (continuationWinrate != null) {
                lastWr = continuationWinrate;
                wr = continuationWinrate;
              }
              lastScore = node.getData().scoreMean;
              if (!node.getData().blackToPlay) {
                lastScore = -lastScore;
              }
              // g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
              topOfVariation = Optional.empty();
              if (node.getData().getPlayouts() == 0) {
                lastNodeOk = false;
              }
            }
            if (Lizzie.config.showWinrateLine) {
              if (node == curMove
                  || (curMove.previous().isPresent()
                      && node == curMove.previous().get()
                      && curMove.getData().getPlayouts() <= 0)) {
                g.setColor(Lizzie.config.winrateLineColor);
                g.fillOval(
                    posx + (movenum * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                cwr = wr;
                cmovenum = movenum;
              }
            }
            lastOkMove = lastNodeOk ? movenum : -1;
          } else {
            lastNodeOk = false;
            if (node == curMove) {
              // Draw a vertical line at the current move
              Stroke previousStroke = g.getStroke();
              int x = posx + (movenum * width / numMoves);
              g.setStroke(new BasicStroke(2));
              g.setColor(new Color(120, 220, 255, 200));
              g.drawLine(x, posy, x, posy + height);
              // Show move number
              if (!noC) {
                String moveNumString = "" + node.getData().moveNumber;
                g.setFont(
                    new Font(
                        Config.sysDefaultFontName,
                        Font.BOLD,
                        largeEnough ? Utils.zoomOut(12) : 12));
                g.setColor(Color.WHITE);
                int moveNum = node.getData().moveNumber;
                if (moveNum < 10)
                  g.drawString(
                      moveNumString,
                      moveNum < numMoves / 2 ? x + 3 : x - 10,
                      posy + height - strokeRadius);
                else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber
                    > 99)
                  g.drawString(
                      moveNumString,
                      moveNum < numMoves / 2 ? x + 3 : x - 22,
                      posy + height - strokeRadius);
                else
                  g.drawString(
                      moveNumString,
                      moveNum < numMoves / 2 ? x + 3 : x - 16,
                      posy + height - strokeRadius);
              }
              g.setStroke(previousStroke);
            }
          }

          if (mouseOverNode != null && node == mouseOverNode) {
            Stroke previousStroke = g.getStroke();
            int x = posx + (movenum * width / numMoves);
            g.setStroke(dashed);

            g.setColor(new Color(100, 200, 255, 180));

            g.drawLine(x, posy, x, posy + height);
            // Show move number
            String moveNumString = "" + node.getData().moveNumber;
            //    int mw = g.getFontMetrics().stringWidth(moveNumString);
            int margin = strokeRadius;
            // int mx = x - posx < width / 2 ? x + margin : x - mw - margin;
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(12) : 12);
            g.setFont(f);
            g.setColor(Color.WHITE);
            int moveNum = node.getData().moveNumber;
            if (wr < 3) {
              int fontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
              if (moveNum < 10)
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 10,
                    posy + fontHeight - margin);
              else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 22,
                    posy + fontHeight - margin);
              else
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 16,
                    posy + fontHeight - margin);
            } else {
              if (moveNum < 10)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 10, posy + height - margin);
              else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 22, posy + height - margin);
              else
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 16, posy + height - margin);
            }
            if (Lizzie.config.showWinrateLine) {
              if (node.getData().getPlayouts() > 0) {
                mwr = wr;
                mmovenum = movenum;
              }
            }
            g.setStroke(previousStroke);
          }

          node = node.previous().get();
          movenum--;
        }
        g.setStroke(new BasicStroke(1));

      } else if (mode == 1) {
        //    boolean isMain = node.isMainTrunk();
        while (node.previous().isPresent()) {
          int currentMoveIndex = node.getData().moveNumber - 1;
          double wr = node.getData().winrate;
          double score = node.getData().scoreMean;
          int playouts = node.getData().getPlayouts();
          if (node == curMove) {
            //            if (Lizzie.config.dynamicWinrateGraphWidth
            //                && node.getData().moveNumber - 1 > this.numMovesOfPlayed) {
            //              this.numMovesOfPlayed = node.getData().moveNumber - 1;
            //              numMoves = this.numMovesOfPlayed;
            //            }
            // Draw a vertical line at the current move
            // Stroke previousStroke = g.getStroke();
            Stroke previousStroke = g.getStroke();
            int x = posx + (currentMoveIndex * width / numMoves);
            g.setStroke(new BasicStroke(2));
            g.setColor(new Color(120, 220, 255, 200));
            if (curMove != Lizzie.board.getHistory().getEnd())
              g.drawLine(x, posy, x, posy + height);

            // Show move number
            String moveNumString = "" + node.getData().moveNumber;
            //   int mw = g.getFontMetrics().stringWidth(moveNumString);
            int margin = strokeRadius;
            //       int mx = x - posx < width / 2 ? x + margin : x - mw - margin;
            //            if (node.getData().blackToPlay) {
            //              g.setColor(Color.WHITE);
            //            } else {
            //              g.setColor(Color.BLACK);
            //            }
            if (Lizzie.board.getHistory().getCurrentHistoryNode()
                != Lizzie.board.getHistory().getEnd()) {
              Font f =
                  new Font(
                      Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(12) : 12);
              g.setFont(f);
              g.setColor(Color.WHITE);
              int moveNum = Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber;
              if (moveNum < 10)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 10, posy + height - margin);
              else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 22, posy + height - margin);
              else
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 16, posy + height - margin);
            }
            g.setStroke(previousStroke);
          }
          if (playouts > 0) {
            if (wr < 0) {
              wr = 100 - lastWr;
              score = lastScore;
            } else if (!node.getData().blackToPlay) {
              wr = 100 - wr;
              score = -score;
            }
            // if (Lizzie.frame.isPlayingAgainstLeelaz
            // && Lizzie.frame.playerIsBlack == !node.getData().blackToPlay) {
            // wr = lastWr;
            // }

            if (lastOkMove >= 0 && Math.abs(currentMoveIndex - lastOkMove) < 25) {
              if (Lizzie.config.showBlunderBar) {
                double lastMoveRate = Math.abs(lastWr - wr);
                double lastMoveScoreRate = Math.abs(lastScore - score);
                gBlunder.setColor(getBlunderColor(lastMoveRate, lastMoveScoreRate));
                int lastHeight = Math.min(15, Math.max(6, height / 12));
                int leftIndex = Math.min(lastOkMove, currentMoveIndex);
                int rightIndex = Math.max(lastOkMove, currentMoveIndex);
                int rectWidth =
                    Math.max(
                        Lizzie.config.minimumBlunderBarWidth,
                        (int) (rightIndex * width / numMoves)
                            - (int) (leftIndex * width / numMoves));
                gBlunder.fillRect(
                    posx + (int) (leftIndex * width / numMoves),
                    blunderBottom - lastHeight,
                    rectWidth + 1,
                    lastHeight);
              }

              //        if (isMain) {
              g.setColor(new Color(100, 180, 255));
              g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
              //              } else {
              //                g.setColor(Color.BLACK);
              //                g.setStroke(dashed);
              //              }
              //              if (lastNodeOk) g.setStroke(new BasicStroke(2f));
              //              else g.setStroke(new BasicStroke(1f));
              // g.setColor(Color.BLACK);
              if (Lizzie.config.showWinrateLine) {
                g.drawLine(
                    posx + (lastOkMove * width / numMoves),
                    posy + height - (int) (lastWr * height / 100),
                    posx + (currentMoveIndex * width / numMoves),
                    posy + height - (int) (wr * height / 100));
                //       if (isMain) {
                g.setColor(whiteColor);
                g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
                //              } else {
                //                g.setColor(Color.WHITE);
                //                g.setStroke(dashed);
                //              }
                //   if (lastNodeOk) g.setStroke(new BasicStroke(2f));
                //    else g.setStroke(new BasicStroke(1f));
                // g.setColor(Color.WHITE);
                g.drawLine(
                    posx + (lastOkMove * width / numMoves),
                    posy + height - (int) ((100 - lastWr) * height / 100),
                    posx + (currentMoveIndex * width / numMoves),
                    posy + height - (int) ((100 - wr) * height / 100));
              }
            }
            if (Lizzie.config.showWinrateLine) {
              if (node == curMove
                  || (curMove.previous().isPresent()
                      && node == curMove.previous().get()
                      && curMove.getData().getPlayouts() <= 0)) {
                g.setColor(new Color(100, 180, 255));
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(f);

                String wrString = String.format(Locale.ENGLISH, "%.1f", wr);
                int stringWidth = g.getFontMetrics().stringWidth(wrString);
                int x = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                x = Math.max(x, origParams[0]);
                x = Math.min(x, origParams[0] + origParams[2] - stringWidth);

                if (wr > 50) {
                  if (wr > 90) {
                    g.drawString(
                        wrString, x, posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString, x, posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  }
                } else {
                  if (wr < 10) {
                    g.drawString(
                        wrString, x, posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString, x, posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS);
                  }
                }
                g.setColor(whiteColor);
                Font fw =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(fw);
                g.setColor(Color.WHITE);
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(
                        posy + height - (int) ((100 - wr) * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);

                wrString = String.format(Locale.ENGLISH, "%.1f", 100 - wr);
                stringWidth = g.getFontMetrics().stringWidth(wrString);
                x = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                x = Math.max(x, origParams[0]);
                x = Math.min(x, origParams[0] + origParams[2] - stringWidth);

                if (wr > 50) {
                  if (wr < 90) {
                    g.drawString(
                        wrString,
                        x,
                        posy + (height - (int) ((100 - wr) * height / 100)) + 6 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        x,
                        posy + (height - (int) ((100 - wr) * height / 100)) - 2 * DOT_RADIUS);
                  }
                } else {
                  if (wr > 10) {
                    g.drawString(
                        wrString,
                        x,
                        posy + (height - (int) ((100 - wr) * height / 100)) - 2 * DOT_RADIUS);
                  } else {
                    g.drawString(
                        wrString,
                        x,
                        posy + (height - (int) ((100 - wr) * height / 100)) + 6 * DOT_RADIUS);
                  }
                }
              }
            }
            lastWr = wr;
            lastScore = score;
            lastNodeOk = true;
            // Check if we were in a variation and has reached the main trunk
            //            if (topOfVariation.isPresent() && topOfVariation.get() == node) {
            //              // Reached top of variation, go to end of main trunk before continuing
            //              while (node.next().isPresent()) {
            //                node = node.next().get();
            //              }
            //              movenum = node.getData().moveNumber - 1;
            //              lastWr = node.getData().winrate;
            //              if (!node.getData().blackToPlay) lastWr = 100 - lastWr;
            //              // g.setStroke(new BasicStroke(2));
            //              isMain = true;
            //              topOfVariation = Optional.empty();
            //              if (node.getData().getPlayouts() == 0) {
            //                lastNodeOk = false;
            //              }
            //            }
            lastOkMove = lastNodeOk ? currentMoveIndex : -1;
          } else {
            lastNodeOk = false;
          }
          // g.setStroke(new BasicStroke(1));
          node = node.previous().get();
        }
      }
    }
    // 添加是否显示目差
    if (Lizzie.config.showScoreLeadLine) {
      node = curMove;
      while (node.next().isPresent()) {
        node = node.next().get();
      }
      if (numMoves < node.getData().moveNumber - 1) {
        numMoves = node.getData().moveNumber - 1;
      }

      if (numMoves < 1) return;
      lastOkMove = -1;
      movenum = node.getData().moveNumber - 1;
      //    if (Lizzie.config.dynamicWinrateGraphWidth && this.numMovesOfPlayed > 0) {
      //      numMoves = this.numMovesOfPlayed;
      //    }
      if (EngineManager.isEngineGame || Lizzie.board.isPkBoard) {
        setMaxScoreLead(node);
        if (EngineManager.isEngineGame
                && (Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.whiteEngineIndex)
                        .isKatago
                    || Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.whiteEngineIndex)
                        .isSai)
            || Lizzie.board.isPkBoardKataW) {
          double lastscoreMean = -500;
          int curmovenum = -1;
          double drawcurscoreMean = 0;
          if (node.getData().blackToPlay) movenum -= 1;
          if (curMove.getData().blackToPlay && curMove.previous().isPresent())
            curMove = curMove.previous().get();
          if (node.getData().blackToPlay && node.previous().isPresent()) {
            double curscoreMean = 0;
            try {
              curscoreMean = node.previous().get().getData().scoreMean;
            } catch (Exception ex) {
            }
            if (EngineManager.isEngineGame) {
              curmovenum = movenum;
              drawcurscoreMean = curscoreMean;
              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            }
            node = node.previous().get();
          }
          while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
            if (node.getData().getPlayouts() > 0) {
              double curscoreMean = node.getData().scoreMean;
              //              if (Math.abs(curscoreMean) > maxcoreMean)
              //            	  maxcoreMean = Math.abs(curscoreMean);

              if (node == curMove) {
                curmovenum = movenum;
                drawcurscoreMean = curscoreMean;
              }
              if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

                if (lastscoreMean > -500) {
                  // Color lineColor = g.getColor();
                  Stroke previousStroke = g.getStroke();
                  g.setColor(Lizzie.config.scoreMeanLineColor);
                  g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                  g.drawLine(
                      posx + ((lastOkMove) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                      posx + ((movenum) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead));
                  g.setStroke(previousStroke);
                }
              }

              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            } else {
              if (EngineManager.isEngineGame
                  && (!node.next().isPresent() || !node.next().get().next().isPresent())) {
                curmovenum = movenum;
                drawcurscoreMean = node.previous().get().previous().get().getData().scoreMean;
              }
            }
            if (node.previous().isPresent() && node.previous().get().previous().isPresent())
              node = node.previous().get().previous().get();
            movenum = movenum - 2;
          }
          if (lastscoreMean > -500) {
            // Color lineColor = g.getColor();
            Stroke previousStroke = g.getStroke();
            g.setColor(Lizzie.config.scoreMeanLineColor);
            g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
            g.drawLine(
                posx + ((lastOkMove) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                posx + ((movenum) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead));
            g.setStroke(previousStroke);
          }
          if (curmovenum > 0) {
            g.setColor(Color.YELLOW);
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 13);
            g.setFont(f);
            double scoreHeight = convertScoreLead(drawcurscoreMean) * height / 2 / maxScoreLead;
            int mScoreHeight = posy + height / 2 - (int) scoreHeight - 3;
            int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
            int up = origParams[1] + fontHeigt;
            int down = origParams[1] + origParams[3];
            mScoreHeight = Math.max(up, mScoreHeight);
            mScoreHeight = Math.min(down, mScoreHeight);
            String scoreString = String.format(Locale.ENGLISH, "%.1f", drawcurscoreMean);
            int stringWidth = g.getFontMetrics().stringWidth(scoreString);
            int x = posx + (curmovenum * width / numMoves) - stringWidth / 2;
            x = Math.max(x, origParams[0]);
            x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
            g.drawString(scoreString, x, mScoreHeight);
          }
        } else if (EngineManager.isEngineGame
                && (Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.blackEngineIndex)
                        .isKatago
                    || Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.blackEngineIndex)
                        .isSai)
            || Lizzie.board.isPkBoardKataB) {
          double lastscoreMean = -500;
          int curmovenum = -1;
          double drawcurscoreMean = 0;
          if (!node.getData().blackToPlay) movenum -= 1;
          if (!node.getData().blackToPlay && node.previous().isPresent()) {
            double curscoreMean = 0;
            try {
              curscoreMean = node.previous().get().getData().scoreMean;
            } catch (Exception ex) {
            }
            if (EngineManager.isEngineGame) {
              curmovenum = movenum;
              drawcurscoreMean = curscoreMean;
              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            }
            node = node.previous().get();
          }
          if (!curMove.getData().blackToPlay && curMove.previous().isPresent())
            curMove = curMove.previous().get();
          while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
            if (node.getData().getPlayouts() > 0) {

              double curscoreMean = node.getData().scoreMean;
              //              if (Math.abs(curscoreMean) > maxcoreMean)
              //            	  maxcoreMean = Math.abs(curscoreMean);

              if (node == curMove) {
                curmovenum = movenum;
                drawcurscoreMean = curscoreMean;
              }
              if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

                if (lastscoreMean > -500) {
                  // Color lineColor = g.getColor();
                  Stroke previousStroke = g.getStroke();
                  g.setColor(Lizzie.config.scoreMeanLineColor);
                  g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                  g.drawLine(
                      posx + ((lastOkMove) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                      posx + ((movenum) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead));
                  g.setStroke(previousStroke);
                }
              }

              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            } else {
              if (EngineManager.isEngineGame
                  && (!node.next().isPresent() || !node.next().get().next().isPresent())) {
                curmovenum = movenum;
                drawcurscoreMean = node.previous().get().previous().get().getData().scoreMean;
              }
            }
            if (node.previous().isPresent() && node.previous().get().previous().isPresent())
              node = node.previous().get().previous().get();
            movenum = movenum - 2;
          }
          if (lastscoreMean > -500) {
            // Color lineColor = g.getColor();
            Stroke previousStroke = g.getStroke();
            g.setColor(Lizzie.config.scoreMeanLineColor);
            g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
            g.drawLine(
                posx + ((lastOkMove) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                posx + ((movenum) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead));
            g.setStroke(previousStroke);
          }
          if (curmovenum > 0) {
            g.setColor(Color.YELLOW);
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 13);
            g.setFont(f);
            double scoreHeight = convertScoreLead(drawcurscoreMean) * height / 2 / maxScoreLead;
            int mScoreHeight = posy + height / 2 - (int) scoreHeight - 3;
            int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
            int up = origParams[1] + fontHeigt;
            int down = origParams[1] + origParams[3];
            mScoreHeight = Math.max(up, mScoreHeight);
            mScoreHeight = Math.min(down, mScoreHeight);
            String scoreString = String.format(Locale.ENGLISH, "%.1f", drawcurscoreMean);
            int stringWidth = g.getFontMetrics().stringWidth(scoreString);
            int x = posx + (curmovenum * width / numMoves) - stringWidth / 2;
            x = Math.max(x, origParams[0]);
            x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
            g.drawString(scoreString, x, mScoreHeight);
          }
        }
      } else if (Lizzie.leelaz.isSai || Lizzie.leelaz.isKatago || Lizzie.board.isKataBoard) {
        setMaxScoreLead(node);
        double lastscoreMean = -500;
        while (node.previous().isPresent()) {
          if (node.getData().getPlayouts() > 0) {

            double curscoreMean = node.getData().scoreMean;

            if (!node.getData().blackToPlay) {
              curscoreMean = -curscoreMean;
            }
            if (Lizzie.config.showKataGoScoreLeadWithKomi)
              curscoreMean = curscoreMean + Lizzie.board.getHistory().getGameInfo().getKomi();
            //            if (Math.abs(curscoreMean) > maxcoreMean)
            //            	maxcoreMean = Math.abs(curscoreMean);

            if (node == curMove
                || (curMove.previous().isPresent()
                    && node == curMove.previous().get()
                    && curMove.getData().getPlayouts() <= 0)) {
              curScoreMoveNum = movenum;
              drawCurSoreMean = curscoreMean;
            }
            if (mouseOverNode != null && node == mouseOverNode) {
              mScoreMoveNum = movenum;
              drawmSoreMean = curscoreMean;
            }
            if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

              if (lastscoreMean > -500) {
                // Color lineColor = g.getColor();
                Stroke previousStroke = g.getStroke();
                g.setColor(Lizzie.config.scoreMeanLineColor);
                //                if (!node.isMainTrunk()) {
                //                  g.setStroke(dashed);
                //                } else
                g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                g.drawLine(
                    posx + (lastOkMove * width / numMoves),
                    posy
                        + height / 2
                        - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                    posx + (movenum * width / numMoves),
                    posy
                        + height / 2
                        - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead));
                g.setStroke(previousStroke);
              }
            }

            lastscoreMean = curscoreMean;
            lastOkMove = movenum;
          }

          node = node.previous().get();
          movenum--;
        }
      }
      // g.setStroke(new BasicStroke(1));

      // record parameters for calculating moveNumber
    }
    int mwrHeight = -1;
    int mWinFontHeight = -1;
    int oriMWrHeight = -1;
    int mx = -1;
    if (mwr >= 0) {
      g.setColor(Color.RED);
      g.fillOval(
          posx + (mmovenum * width / numMoves) - DOT_RADIUS,
          clampDotY(posy + height - (int) (mwr * height / 100) - DOT_RADIUS, DOT_RADIUS),
          DOT_RADIUS * 2,
          DOT_RADIUS * 2);
      g.setColor(Color.WHITE);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
      g.setFont(f);
      oriMWrHeight = posy + (height - (int) (mwr * height / 100));
      mwrHeight = oriMWrHeight + (mwr < 10 ? -5 : (mwr > 90 ? 6 : -2) * DOT_RADIUS);
      mWinFontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      if (mwrHeight > origParams[1] + origParams[3]) {
        mwrHeight = origParams[1] + origParams[3] - 2;
      }

      String mwrString = String.format(Locale.ENGLISH, "%.1f", mwr);
      int stringWidth = g.getFontMetrics().stringWidth(mwrString);
      int x = posx + (mmovenum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      mx = x;
      g.drawString(mwrString, x, mwrHeight);
    }
    if (mScoreMoveNum >= 0) {
      //        if (Lizzie.config.dynamicWinrateGraphWidth
      //            && node.getData().moveNumber - 1 > this.numMovesOfPlayed) {
      //          this.numMovesOfPlayed = node.getData().moveNumber - 1;
      //          numMoves = this.numMovesOfPlayed;
      //        }
      g.setColor(Color.YELLOW);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 14);
      g.setFont(f);
      double scoreHeight = convertScoreLead(drawmSoreMean) * height / 2 / maxScoreLead;
      int mScoreHeight = posy + height / 2 - (int) scoreHeight - 3;
      int oriScoreHeight = mScoreHeight;
      int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      int up = origParams[1] + fontHeigt;
      int down = origParams[1] + origParams[3];
      mScoreHeight = Math.max(up, mScoreHeight);
      mScoreHeight = Math.min(down, mScoreHeight);
      int heightDiff = Math.abs(mwrHeight - mScoreHeight);

      if (heightDiff < fontHeigt) {
        if (oriScoreHeight < oriMWrHeight) {
          if (mwrHeight - mWinFontHeight - 1 >= up) mScoreHeight = mwrHeight - mWinFontHeight - 1;
          else mScoreHeight = mwrHeight + fontHeigt + 1;
        } else if (mwrHeight + fontHeigt + 1 <= down) mScoreHeight = mwrHeight + fontHeigt + 1;
        else mScoreHeight = mwrHeight - mWinFontHeight - 1;
      }
      if (mScoreHeight > origParams[1] + origParams[3]) {
        mScoreHeight = Math.max(origParams[1] + origParams[3], mwrHeight - mWinFontHeight);
      }
      String scoreString = String.format(Locale.ENGLISH, "%.1f", drawmSoreMean);
      int stringWidth = g.getFontMetrics().stringWidth(scoreString);
      int x = posx + (mScoreMoveNum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      g.drawString(scoreString, x, mScoreHeight);
    }

    int cwrHeight = -1;
    int winFontHeight = -1;
    int oriWrHeight = -1;
    noC = false;
    if (cwr >= 0) {
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
      g.setFont(f);
      g.setColor(Color.WHITE);
      oriWrHeight = posy + (height - (int) (cwr * height / 100));
      cwrHeight = oriWrHeight + (cwr < 10 ? -5 : (cwr > 90 ? 6 : -2) * DOT_RADIUS);
      winFontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      if (cwrHeight > origParams[1] + origParams[3]) {
        cwrHeight = origParams[1] + origParams[3] - 2;
      }
      String wrString = String.format(Locale.ENGLISH, "%.1f", cwr);
      int stringWidth = g.getFontMetrics().stringWidth(wrString);
      int x = posx + (cmovenum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      if (mx >= 0) {
        if (Math.abs(x - mx) < stringWidth) noC = true;
      }
      if (!noC) g.drawString(wrString, x, cwrHeight);
    }
    if (curScoreMoveNum >= 0 && !noC) {
      g.setColor(Color.YELLOW);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 14);
      g.setFont(f);
      double scoreHeight = convertScoreLead(drawCurSoreMean) * height / 2 / maxScoreLead;
      int cScoreHeight = posy + height / 2 - (int) scoreHeight - 3;
      int oriScoreHeight = cScoreHeight;
      int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      int up = origParams[1] + fontHeigt;
      int down = origParams[1] + origParams[3];
      cScoreHeight = Math.max(up, cScoreHeight);
      cScoreHeight = Math.min(down, cScoreHeight);
      int heightDiff = Math.abs(cwrHeight - cScoreHeight);

      if (heightDiff < fontHeigt) {
        if (heightDiff <= fontHeigt / 3 && scoreAjustMove == curScoreMoveNum) {
          if (scoreAjustBelow) cScoreHeight = cwrHeight + fontHeigt + 1;
          else cScoreHeight = cwrHeight - winFontHeight - 1;
        } else {
          if (oriScoreHeight < oriWrHeight) {
            if (cwrHeight - winFontHeight - 1 >= up) {
              cScoreHeight = cwrHeight - winFontHeight - 1;
              scoreAjustBelow = false;
            } else {
              cScoreHeight = cwrHeight + fontHeigt + 1;
              scoreAjustBelow = true;
            }
          } else if (cwrHeight + fontHeigt + 1 <= down) {
            cScoreHeight = cwrHeight + fontHeigt + 1;
            scoreAjustBelow = true;
          } else {
            cScoreHeight = cwrHeight - winFontHeight - 1;
            scoreAjustBelow = false;
          }
          if (heightDiff <= fontHeigt / 3) {
            scoreAjustMove = curScoreMoveNum;
          } else scoreAjustMove = -1;
        }
      }
      String scoreString = String.format(Locale.ENGLISH, "%.1f", drawCurSoreMean);
      int stringWidth = g.getFontMetrics().stringWidth(scoreString);
      int x = posx + (curScoreMoveNum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      g.drawString(scoreString, x, cScoreHeight);
    }
    drawLineLegend(gBackground, origParams[0], origParams[1], origParams[2]);

    params[0] = posx;
    params[1] = posy;
    params[2] = width;
    params[3] = height;
    params[4] = numMoves;
    BoardHistoryNode graphBaseNode = curMove;
    List<GraphPoint> renderedAnchors = buildGraphAnchorPoints(graphBaseNode);
    drawGraphAnchors(g, renderedAnchors);
    QuickOverviewLayout quickOverviewLayout =
        drawQuickOverview(g, gBlunder, gBackground, curMove, posx, width, numMoves);
    rememberRenderedPointSources(quickOverviewLayout, renderedAnchors);
  }

  private void drawLineLegend(Graphics2D g, int graphX, int graphY, int graphWidth) {
    if (graphWidth < 240) return;
    Font font =
        new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11);
    g.setFont(font);
    FontMetrics fm = g.getFontMetrics();

    boolean hasScoreLead = Lizzie.config.showScoreLeadLine;
    boolean isTwoLine = mode == 1;

    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Color> colors = new ArrayList<>();

    if (isTwoLine) {
      labels.add("\u9ed1\u80dc\u7387");
      colors.add(new Color(100, 180, 255));
      labels.add("\u767d\u80dc\u7387");
      colors.add(whiteColor);
    } else {
      labels.add("\u80dc\u7387");
      colors.add(
          Lizzie.config.winrateLineColor != null
              ? Lizzie.config.winrateLineColor
              : new Color(100, 180, 255));
    }
    if (hasScoreLead) {
      labels.add("\u76ee\u5dee");
      colors.add(
          Lizzie.config.scoreMeanLineColor != null
              ? Lizzie.config.scoreMeanLineColor
              : Color.YELLOW);
    }

    int lineLen = largeEnough ? Utils.zoomOut(16) : 16;
    int gap = largeEnough ? Utils.zoomOut(12) : 12;
    int innerPad = 5;

    int totalWidth = 0;
    for (String label : labels) {
      totalWidth += lineLen + innerPad + fm.stringWidth(label) + gap;
    }
    totalWidth -= gap;

    int paddingX = 8;
    int paddingY = 3;
    int boxWidth = totalWidth + paddingX * 2;
    int boxHeight = fm.getHeight() + paddingY * 2;
    int boxX = graphX + graphWidth - boxWidth - 6;
    int boxY = graphY + 4;

    g.setColor(new Color(0, 0, 0, 150));
    g.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
    g.setColor(new Color(255, 255, 255, 25));
    g.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);

    int cx = boxX + paddingX;
    int textY = boxY + paddingY + fm.getAscent();
    int lineY = textY - fm.getAscent() / 2;
    Stroke prevStroke = g.getStroke();
    g.setStroke(new BasicStroke(2.0f));
    for (int i = 0; i < labels.size(); i++) {
      g.setColor(colors.get(i));
      g.drawLine(cx, lineY, cx + lineLen, lineY);
      cx += lineLen + innerPad;
      g.setColor(new Color(210, 210, 210));
      g.drawString(labels.get(i), cx, textY);
      cx += fm.stringWidth(labels.get(i)) + gap;
    }
    g.setStroke(prevStroke);
  }

  private QuickOverviewLayout drawQuickOverview(
      Graphics2D g,
      Graphics2D gBlunder,
      Graphics2D gBackground,
      BoardHistoryNode curMove,
      int posx,
      int width,
      int numMoves) {
    QuickOverviewLayout layout = buildQuickOverviewLayout(curMove, posx, width, numMoves, g);
    if (layout == null) return null;
    occludeMainGraphUnderQuickOverview(g, gBlunder, layout);

    Color overviewLineColor =
        Lizzie.config.winrateLineColor != null
            ? Lizzie.config.winrateLineColor.brighter()
            : new Color(255, 208, 84);
    Stroke previousStroke = g.getStroke();

    gBackground.setColor(new Color(15, 20, 28, 205));
    gBackground.fillRoundRect(
        layout.overviewX - 2,
        layout.overviewY,
        layout.overviewWidth + 4,
        layout.overviewHeight,
        12,
        12);
    gBackground.setColor(new Color(255, 255, 255, 65));
    gBackground.drawRoundRect(
        layout.overviewX - 2,
        layout.overviewY,
        layout.overviewWidth + 4,
        layout.overviewHeight,
        12,
        12);
    gBackground.setColor(new Color(255, 255, 255, 40));
    int centerY = layout.innerY + layout.innerHeight / 2;
    gBackground.drawLine(layout.innerX, centerY, layout.innerX + layout.innerWidth, centerY);

    QuickOverviewPoint lastPoint = null;
    for (QuickOverviewPoint point : layout.points) {
      QuickOverviewMove move = point.move;

      if (move.hasAnalysis && move.swing >= layout.issueThreshold) {
        int barHeight =
            Math.max(
                3, (int) Math.round(move.swing * layout.innerHeight * 0.75 / layout.swingScale));
        gBlunder.setColor(
            quickOverviewBarColor(move.swing, layout.issueThreshold, layout.swingScale));
        gBlunder.fillRoundRect(
            point.x - layout.barWidth / 2,
            layout.innerY + layout.innerHeight - barHeight,
            layout.barWidth,
            barHeight,
            4,
            4);
      }

      if (lastPoint != null && move.connectsToPrevious) {
        g.setColor(
            lastPoint.move.hasAnalysis && move.hasAnalysis
                ? overviewLineColor
                : new Color(180, 180, 180, 140));
        g.setStroke(new BasicStroke(Math.max(1.6f, (float) Lizzie.config.winrateStrokeWidth)));
        g.drawLine(lastPoint.x, lastPoint.y, point.x, point.y);
      }
      g.setColor(quickOverviewDotColor(move, overviewLineColor));
      g.fillOval(
          point.x - layout.dotSize / 2,
          point.y - layout.dotSize / 2,
          layout.dotSize,
          layout.dotSize);
      lastPoint = point;
    }
    g.setStroke(previousStroke);

    QuickOverviewPoint currentPoint = findQuickOverviewPoint(layout.points, curMove);
    QuickOverviewPoint hoverPoint = findQuickOverviewPoint(layout.points, mouseOverNode);

    if (currentPoint != null) {
      g.setColor(new Color(255, 255, 255, 170));
      g.drawLine(currentPoint.x, layout.innerY, currentPoint.x, layout.innerY + layout.innerHeight);
      g.fillOval(
          currentPoint.x - layout.dotSize / 2,
          currentPoint.y - layout.dotSize / 2,
          layout.dotSize,
          layout.dotSize);
    }

    if (hoverPoint != null) {
      g.setColor(new Color(61, 204, 255, 230));
      g.drawLine(hoverPoint.x, layout.innerY, hoverPoint.x, layout.innerY + layout.innerHeight);
      g.fillOval(
          hoverPoint.x - layout.dotSize / 2,
          hoverPoint.y - layout.dotSize / 2,
          layout.dotSize,
          layout.dotSize);
      drawQuickOverviewLabel(
          g,
          hoverPoint.move,
          hoverPoint.x,
          layout.overviewY,
          layout.innerX,
          layout.innerX + layout.innerWidth);
    }
    return layout;
  }

  private List<QuickOverviewMove> buildQuickOverviewMoves(BoardHistoryNode curMove) {
    ArrayList<QuickOverviewMove> moves = new ArrayList<>();
    double lastWinrate = 50;
    int lastMoveNumber = 0;
    boolean startsNewSegment = false;
    List<BoardHistoryNode> path = buildGraphPath(curMove);

    for (BoardHistoryNode pathNode : path) {
      if (!pathNode.previous().isPresent()) continue;
      BoardData data = pathNode.getData();
      boolean isSnapshot = data.isSnapshotNode();
      if (!isSnapshot && !isRealHistoryActionNode(data)) continue;
      if (!moves.isEmpty() && pathNode.getData().moveNumber <= lastMoveNumber) {
        startsNewSegment = true;
      }

      double previousWinrate = lastWinrate;
      double currentWinrate = resolveQuickOverviewWinrate(pathNode, previousWinrate);
      boolean connectsToPrevious = !moves.isEmpty() && !startsNewSegment && !isSnapshot;
      lastWinrate = currentWinrate;
      String moveName = quickOverviewMoveName(data);
      boolean hasAnalysis = hasPrimaryAnalysisPayload(data);
      double swing =
          startsNewSegment || isSnapshot
              ? 0
              : resolveQuickOverviewSwing(pathNode, previousWinrate, currentWinrate);
      moves.add(
          new QuickOverviewMove(
              pathNode,
              data.moveNumber,
              moveName,
              currentWinrate,
              swing,
              hasAnalysis,
              connectsToPrevious));
      startsNewSegment = isSnapshot;
      lastMoveNumber = data.moveNumber;
    }
    return moves;
  }

  private String quickOverviewMoveName(BoardData data) {
    if (data.isPassNode()) {
      return "PASS";
    }
    if (data.isMoveNode() && data.lastMove.isPresent()) {
      return Board.convertCoordinatesToName(data.lastMove.get()[0], data.lastMove.get()[1]);
    }
    return "SNAPSHOT";
  }

  private QuickOverviewLayout buildQuickOverviewLayout(
      BoardHistoryNode currentNode, int posx, int width, int numMoves, Graphics2D graphics) {
    if (!canShowQuickOverview(width, numMoves)) return null;

    List<QuickOverviewMove> moves = buildQuickOverviewMoves(currentNode);
    if (moves.size() < 2) return null;

    int overviewHeight = Math.max(42, Math.min(68, origParams[3] / 5));
    int overviewY = origParams[1] + origParams[3] - overviewHeight - 4;
    int innerPadding = Math.max(4, overviewHeight / 8);
    int innerX = posx + innerPadding;
    int innerY = overviewY + innerPadding;
    int innerWidth = Math.max(10, width - innerPadding * 2);
    int innerHeight = Math.max(10, overviewHeight - innerPadding * 2);
    double issueThreshold =
        Lizzie.config.blunderWinThreshold > 0 ? Lizzie.config.blunderWinThreshold : 3.0;
    double winrateScale = quickOverviewWinrateScale(moves);
    double swingScale = quickOverviewSwingScale(moves, issueThreshold);
    int dotSize = Math.max(4, DOT_RADIUS * 2);
    boolean[][] dotMask =
        graphics == null
            ? quickOverviewDotMask(dotSize)
            : quickOverviewDotMask(dotSize, graphics.getRenderingHints());
    return new QuickOverviewLayout(
        buildQuickOverviewPoints(
            moves, innerX, innerY, innerWidth, innerHeight, numMoves, winrateScale),
        posx,
        overviewY,
        width,
        overviewHeight,
        innerX,
        innerY,
        innerWidth,
        innerHeight,
        dotSize,
        dotMask,
        Math.max(2, (int) Math.ceil(innerWidth / Math.max(70.0, numMoves))),
        issueThreshold,
        swingScale);
  }

  private boolean canShowQuickOverview(int width, int numMoves) {
    return origParams[2] >= 180 && origParams[3] >= 120 && width >= 140 && numMoves >= 2;
  }

  private double quickOverviewWinrateScale(List<QuickOverviewMove> moves) {
    double maxWinrateSpread = 10;
    for (QuickOverviewMove move : moves) {
      if (move.hasAnalysis) {
        maxWinrateSpread = Math.max(maxWinrateSpread, Math.abs(move.winrate - 50.0));
      }
    }
    return Math.max(10.0, Math.ceil(maxWinrateSpread / 5.0) * 5.0);
  }

  private double quickOverviewSwingScale(List<QuickOverviewMove> moves, double issueThreshold) {
    double maxSwing = issueThreshold;
    for (QuickOverviewMove move : moves) {
      if (move.hasAnalysis) {
        maxSwing = Math.max(maxSwing, move.swing);
      }
    }
    return Math.max(issueThreshold, Math.ceil(maxSwing / 5.0) * 5.0);
  }

  private List<QuickOverviewPoint> buildQuickOverviewPoints(
      List<QuickOverviewMove> moves,
      int innerX,
      int innerY,
      int innerWidth,
      int innerHeight,
      int numMoves,
      double winrateScale) {
    ArrayList<QuickOverviewPoint> points = new ArrayList<>(moves.size());
    int centerY = innerY + innerHeight / 2;
    for (QuickOverviewMove move : moves) {
      int x = innerX + (move.moveNumber - 1) * innerWidth / numMoves;
      int y =
          centerY
              - (int) Math.round((move.winrate - 50.0) * (innerHeight / 2.0 - 2) / winrateScale);
      points.add(new QuickOverviewPoint(move, x, y));
    }
    return points;
  }

  private double resolveQuickOverviewWinrate(BoardHistoryNode node, double fallback) {
    double wr = node.getData().winrate;
    if (!hasPrimaryAnalysisPayload(node.getData()) || wr < 0) return fallback;
    if (!node.getData().blackToPlay) return 100 - wr;
    return wr;
  }

  private double resolveQuickOverviewSwing(
      BoardHistoryNode node, double previousWinrate, double currentWinrate) {
    if (node.previous().isPresent()) {
      BoardHistoryNode previousNode = node.previous().get();
      if (previousNode.nodeInfo != null
          && previousNode.nodeInfo.moveNum == node.getData().moveNumber
          && previousNode.nodeInfo.analyzed) {
        return Math.abs(previousNode.nodeInfo.diffWinrate);
      }
    }
    return Math.abs(currentWinrate - previousWinrate);
  }

  private QuickOverviewPoint findQuickOverviewPoint(
      List<QuickOverviewPoint> points, BoardHistoryNode targetNode) {
    if (targetNode == null) return null;
    for (QuickOverviewPoint point : points) {
      if (point.move.node == targetNode) return point;
    }
    return null;
  }

  private void drawQuickOverviewLabel(
      Graphics2D g, QuickOverviewMove move, int x, int overviewY, int minX, int maxX) {
    Font previousFont = g.getFont();
    Font font =
        new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(11) : 11);
    g.setFont(font);

    String label =
        String.format(
            Locale.ENGLISH,
            "#%d %s %.1f%% swing %.1f",
            move.moveNumber,
            move.moveName,
            move.winrate,
            move.swing);
    FontMetrics metrics = g.getFontMetrics();
    int paddingX = 6;
    int paddingY = 4;
    int labelWidth = metrics.stringWidth(label) + paddingX * 2;
    int labelHeight = metrics.getAscent() + paddingY * 2;
    int labelX = Math.max(minX, Math.min(x - labelWidth / 2, maxX - labelWidth));
    int labelY = Math.max(origParams[1] + 2, overviewY - labelHeight - 4);

    g.setColor(new Color(0, 0, 0, 210));
    g.fillRoundRect(labelX, labelY, labelWidth, labelHeight, 10, 10);
    g.setColor(new Color(255, 255, 255, 90));
    g.drawRoundRect(labelX, labelY, labelWidth, labelHeight, 10, 10);
    g.setColor(Color.WHITE);
    g.drawString(label, labelX + paddingX, labelY + paddingY + metrics.getAscent() - 1);
    g.setFont(previousFont);
  }

  private Color quickOverviewBarColor(double swing, double threshold, double swingScale) {
    double severity =
        Math.max(0.0, Math.min(1.0, (swing - threshold) / Math.max(1.0, swingScale - threshold)));
    int red = 255;
    int green = Math.max(70, (int) Math.round(176 - 96 * severity));
    int blue = Math.max(36, (int) Math.round(84 - 48 * severity));
    int alpha = Math.min(255, (int) Math.round(150 + 80 * severity));
    return new Color(red, green, blue, alpha);
  }

  private Color quickOverviewDotColor(QuickOverviewMove move, Color analyzedColor) {
    if (move.node != null && move.node.getData().isSnapshotNode()) {
      return new Color(255, 208, 84, 210);
    }
    if (move.hasAnalysis) {
      return new Color(
          analyzedColor.getRed(), analyzedColor.getGreen(), analyzedColor.getBlue(), 210);
    }
    return new Color(200, 200, 200, 170);
  }

  private double convertScoreLead(double coreMean) {
    if (coreMean > maxScoreLead) return maxScoreLead;
    if (coreMean < 0 && Math.abs(coreMean) > maxScoreLead) return -maxScoreLead;
    return coreMean;
  }

  private void setMaxScoreLead(BoardHistoryNode lastMove) {
    resetMaxScoreLead();
    while (lastMove.previous().isPresent()) {
      Double scoreMean = Math.abs(lastMove.getData().scoreMean);
      if (scoreMean > maxScoreLead) maxScoreLead = scoreMean;
      lastMove = lastMove.previous().get();
    }
    Double scoreMean = Math.abs(lastMove.getData().scoreMean);
    if (scoreMean > maxScoreLead) maxScoreLead = scoreMean;
  }

  public void setMouseOverNode(BoardHistoryNode node) {
    mouseOverNode = node;
  }

  public void clearMouseOverNode() {
    mouseOverNode = null;
  }

  public void clearParames() {
    origParams = new int[] {0, 0, 0, 0};
    params = new int[] {0, 0, 0, 0, 0};
    clearRenderedPointSources();
  }

  BoardHistoryNode resolveMoveTargetNode(int x, int y) {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    QuickOverviewLayout quickOverviewLayout = currentQuickOverviewLayout();
    if (quickOverviewLayout != null && isInsideQuickOverview(quickOverviewLayout, x, y)) {
      QuickOverviewPoint point = directQuickOverviewPointHit(quickOverviewLayout, x, y);
      if (point == null && !isFrameTryingMode()) {
        point = columnQuickOverviewPointHit(quickOverviewLayout, x, y);
      }
      return point == null ? null : point.move.node;
    }
    if (!isInsideGraphBounds(x, y)) {
      return null;
    }
    List<GraphPoint> points = currentGraphPoints();
    if (points.isEmpty()) {
      return null;
    }
    GraphPoint point = directGraphPointHit(points, x, y);
    if (point == null) {
      point = columnGraphPointHit(points, x, y);
    }
    return point == null ? null : point.node;
  }

  public int moveNumber(int x, int y) {
    BoardHistoryNode targetNode = resolveMoveTargetNode(x, y);
    return targetNode == null ? -1 : targetNode.getData().moveNumber;
  }

  public void resetMaxScoreLead() {
    maxScoreLead = Lizzie.config.initialMaxScoreLead;
  }

  private BoardHistoryNode currentGraphNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getCurrentHistoryNode();
  }

  private List<BoardHistoryNode> buildGraphPath(BoardHistoryNode currentNode) {
    ArrayList<BoardHistoryNode> path = new ArrayList<>();
    if (currentNode == null) {
      return path;
    }
    BoardHistoryNode node = currentNode;
    while (node.next().isPresent()) {
      node = node.next().get();
    }
    path.add(node);
    while (node.previous().isPresent()) {
      node = node.previous().get();
      path.add(node);
    }
    Collections.reverse(path);
    appendVisibleMainTrunkNodes(currentNode, path);
    return path;
  }

  private void appendVisibleMainTrunkNodes(
      BoardHistoryNode currentNode, List<BoardHistoryNode> path) {
    if (currentNode.isMainTrunk()) {
      return;
    }
    BoardHistoryNode forkNode = currentNode.findTop();
    Optional<BoardHistoryNode> mainNode = forkNode.next();
    while (mainNode.isPresent()) {
      path.add(mainNode.get());
      mainNode = mainNode.get().next();
    }
  }

  private boolean isGraphAnchorNode(BoardData data) {
    return data.isSnapshotNode() || isRealHistoryActionNode(data);
  }

  private boolean isRealHistoryActionNode(BoardData data) {
    return data.isMoveNode() || (data.isPassNode() && !data.dummy);
  }

  private List<GraphPoint> buildGraphAnchorPoints(BoardHistoryNode currentNode) {
    if (!isShowWinrateLineEnabled()) {
      return Collections.emptyList();
    }
    if (params[2] <= 0 || params[3] <= 0 || params[4] <= 0 || currentNode == null) {
      return Collections.emptyList();
    }
    if (isEngineOrPkGraphMode()) {
      return buildEngineGraphAnchorPoints(currentNode);
    }
    if (mode == 1) {
      return buildDualCurveGraphAnchorPoints(currentNode);
    }
    return buildDefaultGraphAnchorPoints(currentNode);
  }

  private List<GraphPoint> buildDefaultGraphAnchorPoints(BoardHistoryNode currentNode) {
    ArrayList<GraphPoint> points = new ArrayList<>();
    BoardHistoryNode node = graphTraversalEnd(currentNode);
    Optional<BoardHistoryNode> variationTop =
        currentNode.isMainTrunk() ? Optional.empty() : Optional.of(currentNode.findTop());
    double lastWinrate = 50;
    while (node.previous().isPresent()) {
      Double displayedWinrate = displayedDefaultAnchorWinrate(node, lastWinrate);
      if (displayedWinrate != null) {
        lastWinrate = displayedWinrate;
        appendGraphPoint(points, node, displayedWinrate.doubleValue());
      }
      if (variationTop.isPresent() && variationTop.get() == node && node.next().isPresent()) {
        node = graphTraversalEnd(node);
        displayedWinrate = displayedDefaultAnchorWinrate(node, lastWinrate);
        if (displayedWinrate != null) {
          lastWinrate = displayedWinrate;
          appendGraphPoint(points, node, displayedWinrate.doubleValue());
        }
        variationTop = Optional.empty();
      }
      node = node.previous().get();
    }
    return points;
  }

  private Double displayedDefaultAnchorWinrate(BoardHistoryNode node, double fallbackWinrate) {
    Double displayedWinrate = displayedGraphWinrate(node, fallbackWinrate);
    if (displayedWinrate != null) {
      return displayedWinrate;
    }
    if (node == null || node.getData() == null) {
      return null;
    }
    BoardData data = node.getData();
    if (!data.isSnapshotNode() || hasPrimaryAnalysisPayload(data)) {
      return null;
    }
    return Math.max(0, Math.min(100, fallbackWinrate));
  }

  private List<GraphPoint> buildEngineGraphAnchorPoints(BoardHistoryNode currentNode) {
    ArrayList<GraphPoint> points = new ArrayList<>();
    BoardHistoryNode node = graphTraversalEnd(currentNode);
    while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
      BoardHistoryNode twoBackNode = node.previous().get().previous().get();
      double currentWinrate = resolveEngineGraphWinrate(node, twoBackNode);
      double twoBackWinrate = resolveEngineGraphBackWinrate(twoBackNode, currentWinrate);
      appendGraphPoint(points, node, currentWinrate);
      appendGraphPoint(points, twoBackNode, twoBackWinrate);
      node = node.previous().get();
    }
    return points;
  }

  private double resolveEngineGraphWinrate(BoardHistoryNode node, BoardHistoryNode twoBackNode) {
    if (hasPrimaryAnalysisPayload(node.getData())) {
      return node.getData().winrate;
    }
    if (hasPrimaryAnalysisPayload(twoBackNode.getData())) {
      return twoBackNode.getData().winrate;
    }
    return 50;
  }

  private double resolveEngineGraphBackWinrate(
      BoardHistoryNode twoBackNode, double fallbackWinrate) {
    if (hasPrimaryAnalysisPayload(twoBackNode.getData())) {
      return twoBackNode.getData().winrate;
    }
    return fallbackWinrate;
  }

  private List<GraphPoint> buildDualCurveGraphAnchorPoints(BoardHistoryNode currentNode) {
    ArrayList<GraphPoint> points = new ArrayList<>();
    BoardHistoryNode node = graphTraversalEnd(currentNode);
    double lastWinrate = 50;
    while (node.previous().isPresent()) {
      Double blackCurveWinrate = displayedDualCurveAnchorWinrate(node, lastWinrate);
      if (blackCurveWinrate != null) {
        lastWinrate = blackCurveWinrate.doubleValue();
        appendGraphPoint(points, node, blackCurveWinrate.doubleValue());
        appendGraphPoint(points, node, 100 - blackCurveWinrate.doubleValue());
      }
      node = node.previous().get();
    }
    return points;
  }

  private Double displayedDualCurveAnchorWinrate(BoardHistoryNode node, double fallbackWinrate) {
    Double displayedWinrate = displayedDualCurveBlackWinrate(node, fallbackWinrate);
    if (displayedWinrate != null) {
      return displayedWinrate;
    }
    if (node == null || node.getData() == null) {
      return null;
    }
    BoardData data = node.getData();
    if (!data.isSnapshotNode() || hasPrimaryAnalysisPayload(data)) {
      return null;
    }
    return Math.max(0, Math.min(100, fallbackWinrate));
  }

  private Double displayedDualCurveBlackWinrate(BoardHistoryNode node, double fallbackWinrate) {
    if (node == null || node.getData() == null || !hasPrimaryAnalysisPayload(node.getData())) {
      return null;
    }
    double winrate = node.getData().winrate;
    if (winrate < 0) {
      winrate = 100 - fallbackWinrate;
    } else if (!node.getData().blackToPlay) {
      winrate = 100 - winrate;
    }
    return winrate;
  }

  private boolean isEngineOrPkGraphMode() {
    return EngineManager.isEngineGame || (Lizzie.board != null && Lizzie.board.isPkBoard);
  }

  private BoardHistoryNode graphTraversalEnd(BoardHistoryNode node) {
    BoardHistoryNode current = node;
    while (current.next().isPresent()) {
      current = current.next().get();
    }
    return current;
  }

  private void appendGraphPoint(List<GraphPoint> points, BoardHistoryNode node, double winrate) {
    if (!node.previous().isPresent() || !isGraphAnchorNode(node.getData())) {
      return;
    }
    points.add(new GraphPoint(node, graphPointX(node.getData().moveNumber), graphPointY(winrate)));
  }

  private void drawGraphAnchors(Graphics2D g, List<GraphPoint> points) {
    if (points.isEmpty()) {
      return;
    }
    int markerHalfWidth = graphAnchorHitHalfWidth(points);
    int markerWidth = graphAnchorMarkerWidth(markerHalfWidth);
    int markerHeight = graphAnchorMarkerHeight();
    for (GraphPoint point : points) {
      g.setColor(graphAnchorColor(point.node.getData()));
      g.fillRect(
          point.x - markerHalfWidth,
          point.y - GRAPH_ANCHOR_HIT_HALF_SIZE,
          markerWidth,
          markerHeight);
    }
  }

  private int graphAnchorMarkerWidth(int markerHalfWidth) {
    return markerHalfWidth * 2 + 1;
  }

  private int graphAnchorMarkerHeight() {
    return GRAPH_ANCHOR_HIT_HALF_SIZE * 2 + 1;
  }

  private Color graphAnchorColor(BoardData data) {
    if (data != null && data.isSnapshotNode()) {
      return new Color(255, 208, 84, 110);
    }
    Color baseColor =
        Lizzie.config.winrateLineColor != null
            ? Lizzie.config.winrateLineColor
            : new Color(100, 180, 255);
    return new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 80);
  }

  private Double displayedGraphWinrate(BoardHistoryNode node, double fallbackWinrate) {
    if (node == null) {
      return null;
    }
    BoardData data = node.getData();
    if (data == null || !hasPrimaryAnalysisPayload(data)) {
      return null;
    }
    double winrate = data.winrate;
    if (winrate < 0) {
      winrate = 100 - fallbackWinrate;
    } else if (!data.blackToPlay) {
      winrate = 100 - winrate;
    }
    return Math.max(0, Math.min(100, winrate));
  }

  private boolean isInsideQuickOverview(QuickOverviewLayout layout, int x, int y) {
    int minX = quickOverviewHitMinX(layout);
    int maxX = quickOverviewHitMaxX(layout);
    int maxY = quickOverviewHitMaxY(layout);
    return minX <= x && x < maxX && layout.overviewY <= y && y < maxY;
  }

  private boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data != null
        && (data.getPlayouts() > 0
            || data.analysisHeaderSlots > 0
            || !Utils.isBlank(data.engineName)
            || (data.bestMoves != null && !data.bestMoves.isEmpty())
            || data.isKataData
            || (data.estimateArray != null && !data.estimateArray.isEmpty()));
  }

  private int quickOverviewHitMinX(QuickOverviewLayout layout) {
    return layout.overviewX - 2;
  }

  private int quickOverviewHitMaxX(QuickOverviewLayout layout) {
    return layout.overviewX + layout.overviewWidth + 2;
  }

  private int quickOverviewHitMaxY(QuickOverviewLayout layout) {
    return layout.overviewY + layout.overviewHeight;
  }

  private Rectangle quickOverviewHitBounds(QuickOverviewLayout layout) {
    int minX = quickOverviewHitMinX(layout);
    int width = quickOverviewHitMaxX(layout) - minX;
    return new Rectangle(minX, layout.overviewY, width, layout.overviewHeight);
  }

  private void occludeMainGraphUnderQuickOverview(
      Graphics2D graphGraphics, Graphics2D blunderGraphics, QuickOverviewLayout layout) {
    Rectangle bounds = quickOverviewHitBounds(layout);
    clearGraphicsRegion(graphGraphics, bounds);
    clearGraphicsRegion(blunderGraphics, bounds);
  }

  private void clearGraphicsRegion(Graphics2D graphics, Rectangle bounds) {
    if (graphics == null || bounds == null || bounds.width <= 0 || bounds.height <= 0) {
      return;
    }
    Composite previousComposite = graphics.getComposite();
    try {
      graphics.setComposite(AlphaComposite.Clear);
      graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
    } finally {
      graphics.setComposite(previousComposite);
    }
  }

  private QuickOverviewPoint directQuickOverviewPointHit(
      QuickOverviewLayout layout, int targetX, int targetY) {
    QuickOverviewPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    for (QuickOverviewPoint point : layout.points) {
      if (!isInsideQuickOverviewDotPixel(layout, point, targetX, targetY)) {
        continue;
      }
      long distance = quickOverviewDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean isInsideQuickOverviewDotPixel(
      QuickOverviewLayout layout, QuickOverviewPoint point, int targetX, int targetY) {
    int dotSize = layout.dotSize;
    if (dotSize <= 0) {
      return false;
    }
    int dotLeft = point.x - dotSize / 2;
    int dotTop = point.y - dotSize / 2;
    int localX = targetX - dotLeft;
    int localY = targetY - dotTop;
    if (localX < 0 || localX >= dotSize || localY < 0 || localY >= dotSize) {
      return false;
    }
    boolean[][] dotMask = quickOverviewDotMask(layout);
    return dotMask[localY][localX];
  }

  private boolean[][] quickOverviewDotMask(QuickOverviewLayout layout) {
    if (layout != null
        && layout.dotMask != null
        && layout.dotMask.length == layout.dotSize
        && layout.dotSize > 0
        && layout.dotMask[0].length == layout.dotSize) {
      return layout.dotMask;
    }
    return quickOverviewDotMask(layout == null ? 0 : layout.dotSize);
  }

  private boolean[][] quickOverviewDotMask(int dotSize) {
    if (dotSize <= 0) {
      return new boolean[0][0];
    }
    boolean[][] mask = quickOverviewDotMaskCache.get(dotSize);
    if (mask != null) {
      return mask;
    }

    BufferedImage dotImage = new BufferedImage(dotSize, dotSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D dotGraphics = dotImage.createGraphics();
    try {
      dotGraphics.fillOval(0, 0, dotSize, dotSize);
    } finally {
      dotGraphics.dispose();
    }

    boolean[][] computedMask = new boolean[dotSize][dotSize];
    for (int y = 0; y < dotSize; y++) {
      for (int x = 0; x < dotSize; x++) {
        computedMask[y][x] = ((dotImage.getRGB(x, y) >>> 24) & 0xff) > 0;
      }
    }
    quickOverviewDotMaskCache.put(dotSize, computedMask);
    return computedMask;
  }

  private boolean[][] quickOverviewDotMask(int dotSize, RenderingHints renderingHints) {
    if (dotSize <= 0) {
      return new boolean[0][0];
    }
    BufferedImage dotImage = new BufferedImage(dotSize, dotSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D dotGraphics = dotImage.createGraphics();
    try {
      if (renderingHints != null) {
        dotGraphics.addRenderingHints(renderingHints);
      }
      dotGraphics.fillOval(0, 0, dotSize, dotSize);
    } finally {
      dotGraphics.dispose();
    }
    boolean[][] computedMask = new boolean[dotSize][dotSize];
    for (int y = 0; y < dotSize; y++) {
      for (int x = 0; x < dotSize; x++) {
        computedMask[y][x] = ((dotImage.getRGB(x, y) >>> 24) & 0xff) > 0;
      }
    }
    return computedMask;
  }

  private long quickOverviewDistanceSquared(QuickOverviewPoint point, int targetX, int targetY) {
    long dx = point.x - targetX;
    long dy = point.y - targetY;
    return dx * dx + dy * dy;
  }

  private QuickOverviewPoint columnQuickOverviewPointHit(
      QuickOverviewLayout layout, int targetX, int targetY) {
    QuickOverviewPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    int hitHalfWidth = quickOverviewColumnHitHalfWidth(layout);
    for (QuickOverviewPoint point : layout.points) {
      if (!isInsideQuickOverviewColumnHitRegion(point, targetX, hitHalfWidth)) {
        continue;
      }
      long distance = quickOverviewDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean isInsideQuickOverviewColumnHitRegion(
      QuickOverviewPoint point, int targetX, int hitHalfWidth) {
    return Math.abs(point.x - targetX) <= hitHalfWidth;
  }

  private GraphPoint directGraphPointHit(List<GraphPoint> points, int targetX, int targetY) {
    GraphPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    int xHitHalfWidth = graphAnchorHitHalfWidth(points);
    for (GraphPoint point : points) {
      if (!isInsideGraphAnchorHitRegion(point, targetX, targetY, xHitHalfWidth)) {
        continue;
      }
      long distance = graphDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private GraphPoint columnGraphPointHit(List<GraphPoint> points, int targetX, int targetY) {
    GraphPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    int hitHalfWidth = graphColumnHitHalfWidth(points);
    for (GraphPoint point : points) {
      if (!isInsideGraphColumnHitRegion(point, targetX, hitHalfWidth)) {
        continue;
      }
      if (hasOverlappingPointsAtColumn(points, point)
          && !isInsideNodeRenderedYRange(points, point.node, targetY)) {
        continue;
      }
      long distance = graphDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean hasOverlappingPointsAtColumn(List<GraphPoint> points, GraphPoint subject) {
    for (GraphPoint other : points) {
      if (other == subject) continue;
      if (other.x == subject.x) {
        return true;
      }
    }
    return false;
  }

  private boolean isInsideNodeRenderedYRange(
      List<GraphPoint> points, BoardHistoryNode node, int targetY) {
    for (GraphPoint other : points) {
      if (other.node != node) continue;
      if (Math.abs(other.y - targetY) <= GRAPH_ANCHOR_HIT_HALF_SIZE) {
        return true;
      }
    }
    return false;
  }

  private boolean isInsideGraphAnchorHitRegion(
      GraphPoint point, int targetX, int targetY, int xHitHalfWidth) {
    return Math.abs(point.x - targetX) <= xHitHalfWidth
        && Math.abs(point.y - targetY) <= GRAPH_ANCHOR_HIT_HALF_SIZE;
  }

  private boolean isInsideGraphColumnHitRegion(GraphPoint point, int targetX, int hitHalfWidth) {
    return Math.abs(point.x - targetX) <= hitHalfWidth;
  }

  private long graphDistanceSquared(GraphPoint point, int targetX, int targetY) {
    long dx = point.x - targetX;
    long dy = point.y - targetY;
    return dx * dx + dy * dy;
  }

  private int graphColumnHitHalfWidth(List<GraphPoint> points) {
    int minSpacing = minPositiveGraphColumnSpacing(points);
    if (minSpacing == Integer.MAX_VALUE) {
      return 0;
    }
    int spacingLimitedHalfWidth = Math.max(0, (minSpacing - 1) / 2);
    return Math.min(GRAPH_ANCHOR_HIT_HALF_SIZE, spacingLimitedHalfWidth);
  }

  private int graphAnchorHitHalfWidth(List<GraphPoint> points) {
    return graphColumnHitHalfWidth(points);
  }

  private int minPositiveGraphColumnSpacing(List<GraphPoint> points) {
    int[] xs = points.stream().mapToInt(p -> p.x).distinct().sorted().toArray();
    int minSpacing = Integer.MAX_VALUE;
    for (int i = 1; i < xs.length; i++) {
      int spacing = xs[i] - xs[i - 1];
      if (spacing > 0 && spacing < minSpacing) {
        minSpacing = spacing;
      }
    }
    return minSpacing;
  }

  private int quickOverviewColumnHitHalfWidth(QuickOverviewLayout layout) {
    int maxHalfWidth = Math.max(1, layout.barWidth);
    int minSpacing = minPositiveQuickOverviewColumnSpacing(layout.points);
    if (minSpacing == Integer.MAX_VALUE) {
      return 0;
    }
    int spacingLimitedHalfWidth = Math.max(0, (minSpacing - 1) / 2);
    return Math.min(maxHalfWidth, spacingLimitedHalfWidth);
  }

  private int minPositiveQuickOverviewColumnSpacing(List<QuickOverviewPoint> points) {
    int minSpacing = Integer.MAX_VALUE;
    for (int i = 1; i < points.size(); i++) {
      int spacing = Math.abs(points.get(i).x - points.get(i - 1).x);
      if (spacing > 0 && spacing < minSpacing) {
        minSpacing = spacing;
      }
    }
    return minSpacing;
  }

  private void rememberRenderedPointSources(QuickOverviewLayout quickOverviewLayout) {
    BoardHistoryNode graphBaseNode = currentGraphNode();
    rememberRenderedPointSources(quickOverviewLayout, buildGraphAnchorPoints(graphBaseNode));
  }

  private void rememberRenderedPointSources(
      QuickOverviewLayout quickOverviewLayout, List<GraphPoint> renderedAnchors) {
    renderedGraphPoints =
        renderedAnchors == null ? Collections.emptyList() : new ArrayList<>(renderedAnchors);
    renderedQuickOverviewLayout = quickOverviewLayout;
    renderedOrigParams = origParams.clone();
    renderedParams = params.clone();
    rememberRenderedStateSnapshot();
  }

  private void clearRenderedPointSources() {
    renderedGraphPoints = Collections.emptyList();
    renderedQuickOverviewLayout = null;
    renderedOrigParams = new int[] {0, 0, 0, 0};
    renderedParams = new int[] {0, 0, 0, 0, 0};
    renderedCurrentGraphNode = null;
    renderedGraphEndNode = null;
    renderedMainEndNode = null;
    renderedMode = 0;
    renderedEngineGame = false;
    renderedPkBoard = false;
    renderedShowWinrateLine = false;
    renderedFrameInPlayMode = false;
  }

  private List<GraphPoint> currentGraphPoints() {
    if (!hasFreshRenderedSources()) {
      return Collections.emptyList();
    }
    return renderedGraphPoints;
  }

  private QuickOverviewLayout currentQuickOverviewLayout() {
    if (!hasFreshRenderedSources()) {
      return null;
    }
    return renderedQuickOverviewLayout;
  }

  private boolean hasFreshRenderedSources() {
    return hasFreshRenderedParams() && hasFreshRenderedState();
  }

  private boolean hasFreshRenderedParams() {
    return Arrays.equals(renderedOrigParams, origParams) && Arrays.equals(renderedParams, params);
  }

  private void rememberRenderedStateSnapshot() {
    BoardHistoryNode currentNode = currentGraphNode();
    renderedCurrentGraphNode = currentNode;
    renderedGraphEndNode = currentNode == null ? null : graphTraversalEnd(currentNode);
    renderedMainEndNode = currentMainEndNode();
    renderedMode = mode;
    renderedEngineGame = EngineManager.isEngineGame;
    renderedPkBoard = Lizzie.board != null && Lizzie.board.isPkBoard;
    renderedShowWinrateLine = isShowWinrateLineEnabled();
    renderedFrameInPlayMode = isFrameInPlayMode();
  }

  private boolean hasFreshRenderedState() {
    boolean sameState =
        (!isFrameTryingMode() || currentGraphNode() == renderedCurrentGraphNode)
            && currentMainEndNode() == renderedMainEndNode
            && mode == renderedMode
            && EngineManager.isEngineGame == renderedEngineGame
            && (Lizzie.board != null && Lizzie.board.isPkBoard) == renderedPkBoard
            && isShowWinrateLineEnabled() == renderedShowWinrateLine
            && isFrameInPlayMode() == renderedFrameInPlayMode;
    if (!sameState) {
      return false;
    }
    return hasFreshRenderedGraphPoints(renderedCurrentGraphNode)
        && hasFreshRenderedQuickOverviewLayout(renderedCurrentGraphNode);
  }

  private boolean hasFreshRenderedGraphPoints(BoardHistoryNode currentNode) {
    return sameGraphPoints(renderedGraphPoints, buildGraphAnchorPoints(currentNode));
  }

  private boolean hasFreshRenderedQuickOverviewLayout(BoardHistoryNode currentNode) {
    QuickOverviewLayout currentLayout =
        buildQuickOverviewLayout(currentNode, params[0], params[2], params[4], null);
    return sameQuickOverviewLayout(renderedQuickOverviewLayout, currentLayout);
  }

  private boolean sameGraphPoints(List<GraphPoint> renderedPoints, List<GraphPoint> currentPoints) {
    if (renderedPoints.size() != currentPoints.size()) {
      return false;
    }
    for (int i = 0; i < renderedPoints.size(); i++) {
      GraphPoint renderedPoint = renderedPoints.get(i);
      GraphPoint currentPoint = currentPoints.get(i);
      if (renderedPoint.node != currentPoint.node
          || renderedPoint.x != currentPoint.x
          || renderedPoint.y != currentPoint.y) {
        return false;
      }
    }
    return true;
  }

  private boolean sameQuickOverviewLayout(
      QuickOverviewLayout rendered, QuickOverviewLayout current) {
    if (rendered == current) {
      return true;
    }
    if (rendered == null || current == null) {
      return false;
    }
    if (rendered.overviewX != current.overviewX
        || rendered.overviewY != current.overviewY
        || rendered.overviewWidth != current.overviewWidth
        || rendered.overviewHeight != current.overviewHeight
        || rendered.innerX != current.innerX
        || rendered.innerY != current.innerY
        || rendered.innerWidth != current.innerWidth
        || rendered.innerHeight != current.innerHeight
        || rendered.dotSize != current.dotSize) {
      return false;
    }
    return sameQuickOverviewPoints(rendered.points, current.points);
  }

  private boolean sameQuickOverviewPoints(
      List<QuickOverviewPoint> renderedPoints, List<QuickOverviewPoint> currentPoints) {
    if (renderedPoints.size() != currentPoints.size()) {
      return false;
    }
    for (int i = 0; i < renderedPoints.size(); i++) {
      QuickOverviewPoint renderedPoint = renderedPoints.get(i);
      QuickOverviewPoint currentPoint = currentPoints.get(i);
      if (renderedPoint.move.node != currentPoint.move.node
          || renderedPoint.x != currentPoint.x
          || renderedPoint.y != currentPoint.y) {
        return false;
      }
    }
    return true;
  }

  private BoardHistoryNode currentMainEndNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getMainEnd();
  }

  private boolean isFrameInPlayMode() {
    return Lizzie.frame != null && Lizzie.frame.isInPlayMode();
  }

  private boolean isShowWinrateLineEnabled() {
    return Lizzie.config != null && Lizzie.config.showWinrateLine;
  }

  private boolean isFrameTryingMode() {
    return Lizzie.frame != null && Lizzie.frame.isTrying;
  }

  private GraphPoint findGraphPoint(List<GraphPoint> points, BoardHistoryNode targetNode) {
    if (targetNode == null) return null;
    for (GraphPoint point : points) {
      if (point.node == targetNode) return point;
    }
    return null;
  }

  private int[] renderedGraphPoint(BoardHistoryNode targetNode) {
    GraphPoint point = findGraphPoint(currentGraphPoints(), targetNode);
    if (point == null) return null;
    return new int[] {point.x, point.y};
  }

  private int[] renderedQuickOverviewPoint(BoardHistoryNode targetNode) {
    QuickOverviewLayout layout = currentQuickOverviewLayout();
    if (layout == null) return null;
    QuickOverviewPoint point = findQuickOverviewPoint(layout.points, targetNode);
    if (point == null) return null;
    return new int[] {point.x, point.y};
  }

  private int graphPointX(int moveNumber) {
    return graphPointXByMoveIndex(moveNumber - 1);
  }

  private int graphPointXByMoveIndex(int moveIndex) {
    int x = params[0] + moveIndex * params[2] / params[4];
    int maxX = params[0] + Math.max(0, params[2] - 1);
    return Math.max(params[0], Math.min(maxX, x));
  }

  private int graphPointY(double winrate) {
    int y = params[1] + params[3] - (int) (winrate * params[3] / 100);
    int maxY = params[1] + Math.max(0, params[3] - 1);
    return Math.max(params[1], Math.min(maxY, y));
  }

  private boolean isInsideGraphBounds(int x, int y) {
    int origPosx = origParams[0];
    int origPosy = origParams[1];
    int origWidth = origParams[2];
    int origHeight = origParams[3];
    return origPosx <= x && x < origPosx + origWidth && origPosy <= y && y < origPosy + origHeight;
  }
}
