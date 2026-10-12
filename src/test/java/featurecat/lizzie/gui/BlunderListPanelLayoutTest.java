package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

class BlunderListPanelLayoutTest {
  private Config oldConfig;
  private ResourceBundle oldBundle;
  private int oldFontSize;

  @BeforeEach
  void isolateConfiguration() throws Exception {
    oldConfig = Lizzie.config;
    oldBundle = Lizzie.resourceBundle;
    oldFontSize = Config.frameFontSize;
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Lizzie.config = (Config) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Config.class);
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.uiFontName = Config.sysDefaultFontName;
    Lizzie.config.useLanguage = AppLocale.SIMPLIFIED_CHINESE.configValue();
    Lizzie.config.commentBackgroundColor = new Color(30, 33, 38);
    Config.frameFontSize = 14;
    Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();
  }

  @AfterEach
  void restoreConfiguration() {
    Lizzie.config = oldConfig;
    Lizzie.resourceBundle = oldBundle;
    Config.frameFontSize = oldFontSize;
  }

  @Test
  void defaultLossOrderBreaksTiesByActualMoveNumber() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(101, 20), row(3, 30), row(7, 20)), null);
          assertEquals(List.of(3, 7, 101), moves(panel));
          assertEquals(ProblemListSort.LOSS_DESC, panel.sortOrder());
        });
  }

  @Test
  void classicSideSelectionIsVisibleOnLightAndDarkBackgrounds() throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    LizzieFrame previousFrame = Lizzie.frame;
    Lizzie.frame = (LizzieFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LizzieFrame.class);
    try {
      edt(
          () -> {
            Lizzie.config.isShowingBlunderTabel = true;
            for (Color background : new Color[] {new Color(242, 245, 244), new Color(30, 33, 38)}) {
              Lizzie.config.commentBackgroundColor = background;
              for (String side : new String[] {"black", "white"}) {
                Lizzie.config.problemListSideFilter = side;
                SidebarHeaderPanel header = new SidebarHeaderPanel(null);
                header.setSize(500, 56);
                BufferedImage image = new BufferedImage(500, 56, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                  graphics.setColor(background);
                  graphics.fillRect(0, 0, 500, 56);
                  header.paint(graphics);
                  FontMetrics metrics =
                      graphics.getFontMetrics(new Font(Lizzie.config.uiFontName, Font.BOLD, 12));
                  SidebarHeaderPanel.HeaderLayout layout =
                      SidebarHeaderPanel.headerLayout(false, true, metrics, 500, "");
                  int x = side.equals("black") ? layout.blackTextX : layout.whiteTextX;
                  Color marker = new Color(image.getRGB(x + 5, layout.sideBaseline + 8));
                  assertEquals(SidebarPanel.accentTextColor(), marker);
                  double a = luminance(background);
                  double b = luminance(marker);
                  assertTrue((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 3.0);
                } finally {
                  graphics.dispose();
                }
              }
            }
          });
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void bothChronologicalOrdersAreNumericAndKeepActualMoveNumbers() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(101, 20), row(3, 30), row(7, 20)), null);
          panel.sortControl().setSelectedItem(ProblemListSort.MOVE_ASC);
          assertEquals(List.of(3, 7, 101), moves(panel));
          panel.sortControl().setSelectedItem(ProblemListSort.MOVE_DESC);
          assertEquals(List.of(101, 7, 3), moves(panel));
          JList<ProblemMoveEntry> list = panel.reviewList();
          JComponent renderer =
              (JComponent)
                  list.getCellRenderer()
                      .getListCellRendererComponent(
                          list, list.getModel().getElementAt(0), 0, false, false);
          String name = renderer.getAccessibleContext().getAccessibleName();
          assertTrue(name.contains("序号1"), name);
          assertTrue(name.contains("第101手"), name);
        });
  }

  @Test
  void savedSortSurvivesRecreationAndDoesNotChangeSideOrThreshold() throws Exception {
    edt(
        () -> {
          Lizzie.config.uiConfig.put("problem-list-side-filter", "white");
          Lizzie.config.uiConfig.put("problem-list-winrate-threshold", 17);
          BlunderListPanel first = panel();
          first.sortControl().setSelectedItem(ProblemListSort.MOVE_DESC);
          JSONObject diskRoundTrip = new JSONObject(Lizzie.config.uiConfig.toString());
          Lizzie.config.uiConfig = diskRoundTrip;
          assertEquals(ProblemListSort.MOVE_DESC, panel().sortOrder());
          assertEquals("white", diskRoundTrip.getString("problem-list-side-filter"));
          assertEquals(17, diskRoundTrip.getInt("problem-list-winrate-threshold"));
        });
  }

  @Test
  void keyboardFocusSelectsVisibleRowWithoutNavigatingOrOverwritingSelection() throws Exception {
    edt(
        () -> {
          AtomicReference<ProblemMoveEntry> navigation = new AtomicReference<>();
          BlunderListPanel panel =
              new BlunderListPanel(navigation::set, () -> ProblemListSideFilter.BLACK);
          List<ProblemMoveEntry> entries = new ArrayList<>();
          for (int i = 1; i <= 30; i++) entries.add(row(i, 50 - i));
          panel.updateSnapshot(snapshot(entries.toArray(new ProblemMoveEntry[0])), null);
          panel.setSize(360, 200);
          layoutTree(panel);
          JList<ProblemMoveEntry> list = panel.reviewList();
          list.scrollRectToVisible(list.getCellBounds(10, 10));
          int firstVisible = list.getFirstVisibleIndex();
          assertTrue(firstVisible > 0);
          FocusEvent focus = new FocusEvent(list, FocusEvent.FOCUS_GAINED);
          for (FocusListener listener : list.getFocusListeners()) listener.focusGained(focus);
          assertEquals(firstVisible, list.getSelectedIndex());
          assertNull(navigation.get());
          list.setSelectedIndex(20);
          for (FocusListener listener : list.getFocusListeners()) listener.focusGained(focus);
          assertEquals(20, list.getSelectedIndex());
          assertNull(navigation.get());
        });
  }

  @Test
  void invalidAndMissingSortHaveSafeDefault() throws Exception {
    edt(
        () -> {
          assertEquals(ProblemListSort.LOSS_DESC, panel().sortOrder());
          Lizzie.config.uiConfig.put("problem-list-sort", "unknown");
          assertEquals(ProblemListSort.LOSS_DESC, panel().sortOrder());
          assertEquals(ProblemListSort.LOSS_DESC, ProblemListSort.fromConfigValue(null));
        });
  }

  @Test
  void filteringRebuildsTheSameViewUsedByNavigation() throws Exception {
    edt(
        () -> {
          AtomicReference<ProblemListSideFilter> side =
              new AtomicReference<>(ProblemListSideFilter.BLACK);
          BlunderListPanel panel = new BlunderListPanel(e -> {}, side::get);
          ProblemListSnapshot data =
              new ProblemListSnapshot(
                  ProblemListMetric.WINRATE_LOSS,
                  List.of(row(13, 25)),
                  List.of(white(18, 40)),
                  100,
                  100,
                  false);
          panel.updateSnapshot(data, null);
          assertEquals(List.of(13), moves(panel));
          side.set(ProblemListSideFilter.WHITE);
          panel.updateSnapshot(data, null);
          assertEquals(List.of(18), moves(panel));
        });
  }

  @Test
  void selectedMoveSurvivesReorderingAndClearsOnNewGame() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          Object game = new Object();
          panel.updateSnapshot(snapshot(row(5, 40), row(9, 20)), game);
          panel.reviewList().setSelectedIndex(1);
          panel.updateSnapshot(snapshot(row(5, 10), row(9, 60)), game);
          assertEquals(9, panel.reviewList().getSelectedValue().moveNumber);
          panel.sortControl().setSelectedItem(ProblemListSort.MOVE_ASC);
          assertEquals(9, panel.reviewList().getSelectedValue().moveNumber);
          panel.updateSnapshot(snapshot(row(5, 10), row(9, 60)), new Object());
          assertNull(panel.reviewList().getSelectedValue());
        });
  }

  @Test
  void removingSelectedMoveClearsSelectionRatherThanSelectingAnotherMove() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(5, 40), row(9, 20)), null);
          panel.reviewList().setSelectedIndex(1);
          panel.updateSnapshot(snapshot(row(5, 40)), null);
          assertNull(panel.reviewList().getSelectedValue());
        });
  }

  @Test
  void refreshPreservesVisibleMoveAndPixelOffsetInLongList() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          ProblemMoveEntry[] rows = new ProblemMoveEntry[150];
          for (int i = 0; i < rows.length; i++) rows[i] = row(i * 2 + 1, 200 - i);
          Object game = new Object();
          panel.updateSnapshot(snapshot(rows), game);
          panel.setSize(360, 300);
          layoutTree(panel);
          JList<ProblemMoveEntry> list = panel.reviewList();
          JViewport viewport = (JViewport) list.getParent();
          list.setFixedCellWidth(viewport.getWidth());
          list.setSize(viewport.getWidth(), list.getPreferredSize().height);
          Rectangle target = list.getCellBounds(50, 50);
          viewport.setViewPosition(new Point(0, target.y + 7));
          assertEquals(50, list.getFirstVisibleIndex());
          rows[100] = row(201, 250);
          panel.updateSnapshot(snapshot(rows), game);
          int first = list.getFirstVisibleIndex();
          assertEquals(101, list.getModel().getElementAt(first).moveNumber);
          assertEquals(7, viewport.getViewPosition().y - list.getCellBounds(first, first).y);
          panel.sortControl().setSelectedItem(ProblemListSort.MOVE_ASC);
          assertEquals(0, viewport.getViewPosition().y);
          viewport.setViewPosition(new Point(0, 200));
          panel.updateSnapshot(snapshot(rows), new Object());
          assertEquals(0, viewport.getViewPosition().y);
        });
  }

  @Test
  void unchangedAnalysisDoesNotResetTheNativeListModel() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(1, 30), row(3, 20)), null);
          List<ListDataEvent> events = new ArrayList<>();
          panel
              .reviewList()
              .getModel()
              .addListDataListener(
                  new ListDataListener() {
                    public void contentsChanged(ListDataEvent event) {
                      events.add(event);
                    }

                    public void intervalAdded(ListDataEvent event) {
                      events.add(event);
                    }

                    public void intervalRemoved(ListDataEvent event) {
                      events.add(event);
                    }
                  });
          panel.reviewList().setSelectedIndex(1);
          panel.updateSnapshot(snapshot(row(1, 30), row(3, 20)), null);
          assertTrue(events.isEmpty());
          assertEquals(3, panel.reviewList().getSelectedValue().moveNumber);
        });
  }

  @Test
  void sortChoicesWrapAtLargeFontsWithoutChangingFieldHeight() throws Exception {
    edt(
        () -> {
          Config.frameFontSize = 20;
          for (AppLocale locale : AppLocale.values()) {
            if (locale == AppLocale.SYSTEM) continue;
            selectLocale(locale);
            BlunderListPanel panel = panel();
            panel.setSize(160, 400);
            panel.doLayout();
            JComboBox<ProblemListSort> combo = panel.sortControl();
            int height = combo.getHeight();
            for (ProblemListSort order : ProblemListSort.values()) {
              combo.setSelectedItem(order);
              panel.doLayout();
              combo.doLayout();
              Component rendered =
                  combo
                      .getRenderer()
                      .getListCellRendererComponent(new JList<>(), order, -1, false, false);
              assertEquals(height, combo.getHeight());
              assertTrue(rendered instanceof JTextArea);
              assertTrue(
                  rendered.getPreferredSize().height <= height - 2,
                  locale + " " + order + " " + rendered.getPreferredSize() + " / " + height);
              for (Component child : combo.getComponents()) {
                if (child instanceof JButton) assertTrue(child.getWidth() <= 24);
              }
            }
          }
        });
  }

  @Test
  void clickTargetsActualSortedEntryAndBlankSpaceDoesNotNavigate() throws Exception {
    edt(
        () -> {
          List<Integer> navigated = new ArrayList<>();
          BlunderListPanel panel =
              new BlunderListPanel(
                  e -> navigated.add(e.moveNumber), () -> ProblemListSideFilter.BLACK);
          panel.updateSnapshot(snapshot(row(3, 20), row(105, 50)), null);
          prepareList(panel, 360, 400);
          JList<ProblemMoveEntry> list = panel.reviewList();
          Rectangle cell = list.getCellBounds(0, 0);
          click(list, 50, cell.y + 10);
          assertEquals(List.of(105), navigated);
          click(list, 50, cell.height - 1);
          click(list, 50, 399);
          click(list, -1, 10);
          assertEquals(List.of(105), navigated);
        });
  }

  @Test
  void snapshotReorderBetweenPressAndReleaseCannotNavigateWrongMove() throws Exception {
    edt(
        () -> {
          List<Integer> navigated = new ArrayList<>();
          BlunderListPanel panel =
              new BlunderListPanel(
                  e -> navigated.add(e.moveNumber), () -> ProblemListSideFilter.BLACK);
          panel.updateSnapshot(snapshot(row(3, 20), row(105, 50)), null);
          prepareList(panel, 360, 400);
          send(panel.reviewList(), MouseEvent.MOUSE_PRESSED, 50, 10);
          panel.updateSnapshot(snapshot(row(3, 70), row(105, 10)), null);
          send(panel.reviewList(), MouseEvent.MOUSE_RELEASED, 50, 10);
          assertTrue(navigated.isEmpty());
        });
  }

  @Test
  void enterAndSpaceUseSelectedEntryWithoutNavigatingOnSelectionAlone() throws Exception {
    edt(
        () -> {
          List<Integer> navigated = new ArrayList<>();
          BlunderListPanel panel =
              new BlunderListPanel(
                  e -> navigated.add(e.moveNumber), () -> ProblemListSideFilter.BLACK);
          panel.updateSnapshot(snapshot(row(3, 20), row(105, 50)), null);
          panel.reviewList().setSelectedIndex(1);
          assertTrue(navigated.isEmpty());
          for (String key : List.of("ENTER", "SPACE")) {
            Object action = panel.reviewList().getInputMap().get(KeyStroke.getKeyStroke(key));
            panel
                .reviewList()
                .getActionMap()
                .get(action)
                .actionPerformed(new ActionEvent(panel, 0, key));
          }
          assertEquals(List.of(3, 3), navigated);
        });
  }

  @Test
  void f6RegionTraversalIncludesTheReviewListAfterSorting() throws Exception {
    java.lang.reflect.Method candidates =
        AccessibilitySupport.class.getDeclaredMethod("focusCandidates", JComponent.class);
    candidates.setAccessible(true);
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(1, 30), row(3, 20)), null);
          JPanel sidebar = new JPanel(new BorderLayout());
          SidebarHeaderPanel header = new SidebarHeaderPanel(null);
          sidebar.add(header, BorderLayout.NORTH);
          sidebar.add(panel, BorderLayout.CENTER);
          Object result = assertDoesNotThrow(() -> candidates.invoke(null, sidebar));
          assertSame(header, ((List<?>) result).get(0));
          assertTrue(((List<?>) result).contains(panel.reviewList()));
          assertTrue(((List<?>) result).contains(panel.sortControl()));
        });
  }

  @Test
  void nativeArrowActionMovesSelectionWithoutJumpingTheBoard() throws Exception {
    edt(
        () -> {
          List<Integer> navigated = new ArrayList<>();
          BlunderListPanel panel =
              new BlunderListPanel(
                  e -> navigated.add(e.moveNumber), () -> ProblemListSideFilter.BLACK);
          panel.updateSnapshot(snapshot(row(1, 30), row(3, 20)), null);
          JList<ProblemMoveEntry> list = panel.reviewList();
          list.setSelectedIndex(0);
          Object next = list.getInputMap().get(KeyStroke.getKeyStroke("DOWN"));
          list.getActionMap().get(next).actionPerformed(new ActionEvent(list, 0, "DOWN"));
          assertEquals(3, list.getSelectedValue().moveNumber);
          assertTrue(navigated.isEmpty());
        });
  }

  @Test
  void unknownScoreIsOmittedAndTooltipUsesPercentagePoints() throws Exception {
    edt(
        () -> {
          ProblemMoveEntry missing = row(17, 20);
          assertFalse(BlunderListPanel.detailLabel(missing).contains("目损"));
          ProblemMoveEntry scored =
              new ProblemMoveEntry(true, 17, "D4", 20, 6.5, true, 1500, false, 5);
          assertTrue(BlunderListPanel.detailLabel(scored).contains("6.5"));
          assertTrue(BlunderListPanel.accessibleRow(scored, 1).contains("20.0个百分点"));
        });
  }

  @Test
  void emptyStatesDistinguishNotAnalyzedRunningAndFiltered() throws Exception {
    edt(
        () -> {
          ResourceBundle bundle = Lizzie.resourceBundle;
          assertEquals("尚无分析数据", BlunderListPanel.emptyStateText(null, bundle));
          assertEquals(
              "正在整理问题手...",
              BlunderListPanel.emptyStateText(
                  new ProblemListSnapshot(
                      ProblemListMetric.WINRATE_LOSS, List.of(), List.of(), 0, 100, true),
                  bundle));
          assertEquals("当前棋手暂无问题手", BlunderListPanel.emptyStateText(snapshot(), bundle));
        });
  }

  @Test
  void allLanguagesAndNarrowRowsWrapWithoutClippingOrShrinkingText() throws Exception {
    edt(
        () -> {
          for (AppLocale locale : AppLocale.values()) {
            if (locale == AppLocale.SYSTEM) continue;
            selectLocale(locale);
            for (int font : new int[] {14, 20}) {
              Config.frameFontSize = font;
              for (int width : new int[] {160, 240, 360, 540}) {
                BlunderListPanel panel = panel();
                panel.updateSnapshot(
                    snapshot(
                        new ProblemMoveEntry(true, 357, "T19", 99.9, 123.4, true, 999999, true, 5)),
                    null);
                prepareList(panel, width, 500);
                JList<ProblemMoveEntry> list = panel.reviewList();
                JPanel cell =
                    (JPanel)
                        list.getCellRenderer()
                            .getListCellRendererComponent(
                                list, list.getModel().getElementAt(0), 0, true, true);
                cell.setSize(cell.getPreferredSize());
                cell.doLayout();
                assertTrue(cell.getHeight() > 0);
                for (Component child : cell.getComponents()) {
                  if (child.getWidth() == 0) continue;
                  assertTrue(child.getX() >= 0 && child.getY() >= 0);
                  assertTrue(child.getX() + child.getWidth() <= width, locale + " " + width);
                  assertTrue(
                      child.getY() + child.getHeight() <= cell.getHeight(), locale + " " + width);
                  assertTrue(child.getFont().getSize() >= font - 1);
                  if (child instanceof JTextArea)
                    assertTrue(child.getPreferredSize().height <= child.getHeight());
                }
                BufferedImage image =
                    new BufferedImage(width, cell.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                cell.paint(g);
                g.dispose();
              }
            }
          }
        });
  }

  @Test
  void listTracksWidthAndOnlyScrollsVertically() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(1, 20), row(3, 30)), null);
          prepareList(panel, 240, 120);
          assertTrue(panel.reviewList().getScrollableTracksViewportWidth());
          JScrollPane scroll =
              (JScrollPane)
                  SwingUtilities.getAncestorOfClass(JScrollPane.class, panel.reviewList());
          assertEquals(
              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
              scroll.getHorizontalScrollBarPolicy());
        });
  }

  @Test
  void lightAndDarkListsRenderWithReadableTextAndOptionalEvidence() throws Exception {
    edt(
        () -> {
          for (boolean dark : new boolean[] {false, true}) {
            Lizzie.config.isAppleStyle = dark;
            Color background = dark ? new Color(30, 33, 38) : new Color(247, 249, 248);
            Lizzie.config.commentBackgroundColor = background;
            for (Color foreground :
                new Color[] {
                  SidebarPanel.primaryTextColor(),
                  SidebarPanel.secondaryTextColor(),
                  SidebarPanel.lossTextColor()
                }) {
              double a = luminance(background);
              double b = luminance(foreground);
              assertTrue((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 4.5);
            }
            for (AppLocale locale : AppLocale.values()) {
              if (locale == AppLocale.SYSTEM) continue;
              selectLocale(locale);
              for (int width : new int[] {240, 360}) {
                BlunderListPanel panel = panel();
                panel.setOpaque(true);
                panel.setBackground(background);
                panel.updateSnapshot(
                    snapshot(
                        new ProblemMoveEntry(true, 157, "D4", 32.8, 12.1, true, 18000, true, 5),
                        row(57, 19.4),
                        row(121, 13.1),
                        row(7, 10.2),
                        row(93, 8.5)),
                    null);
                panel.setSize(width, 620);
                layoutTree(panel);
                panel
                    .reviewList()
                    .setFixedCellWidth(((JViewport) panel.reviewList().getParent()).getWidth());
                layoutTree(panel);
                BufferedImage image = new BufferedImage(width, 620, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                panel.printAll(graphics);
                graphics.dispose();
                String output = System.getProperty("lizzie.qa.problemList.output");
                if (output != null) {
                  java.io.File file =
                      new java.io.File(
                          output,
                          "component-"
                              + locale.name()
                              + "-"
                              + (dark ? "dark" : "light")
                              + "-"
                              + width
                              + ".png");
                  assertDoesNotThrow(
                      () -> {
                        java.nio.file.Files.createDirectories(file.toPath().getParent());
                        javax.imageio.ImageIO.write(image, "png", file);
                      });
                }
              }
            }
          }
        });
  }

  private static double luminance(Color color) {
    double result = 0;
    int[] values = {color.getRed(), color.getGreen(), color.getBlue()};
    double[] weights = {0.2126, 0.7152, 0.0722};
    for (int i = 0; i < values.length; i++) {
      double channel = values[i] / 255.0;
      result +=
          weights[i]
              * (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
    }
    return result;
  }

  @Test
  void toolbarControlsDoNotOverlapAtNarrowWidths() throws Exception {
    edt(
        () -> {
          for (AppLocale locale : AppLocale.values()) {
            selectLocale(locale);
            BlunderListPanel panel = panel();
            for (int width : new int[] {160, 240, 360, 540}) {
              panel.setSize(width, 300);
              panel.doLayout();
              JPanel toolbar = (JPanel) panel.getComponent(0);
              assertFalse(
                  toolbar
                      .getComponent(0)
                      .getBounds()
                      .intersects(toolbar.getComponent(1).getBounds()));
              for (Component child : toolbar.getComponents()) {
                assertTrue(child.getX() + child.getWidth() <= width);
                assertTrue(child.getY() + child.getHeight() <= toolbar.getHeight());
              }
            }
          }
        });
  }

  private static void prepareList(BlunderListPanel panel, int width, int height) {
    panel.setSize(width, height);
    panel.doLayout();
    JList<ProblemMoveEntry> list = panel.reviewList();
    list.setFixedCellWidth(width);
    list.setSize(width, height);
  }

  private static void layoutTree(Container parent) {
    parent.doLayout();
    for (Component child : parent.getComponents()) {
      if (child instanceof Container) layoutTree((Container) child);
    }
  }

  private static void selectLocale(AppLocale locale) {
    Lizzie.resourceBundle = locale.loadBundle();
    Lizzie.config.useLanguage = locale.configValue();
  }

  @Test
  void recycledNarrowRendererAlwaysShowsItsOwnLossWithoutAnotherLayoutPass() throws Exception {
    edt(
        () -> {
          BlunderListPanel panel = panel();
          panel.updateSnapshot(snapshot(row(57, 32.8), row(7, 8.5)), null);
          prepareList(panel, 200, 400);
          JList<ProblemMoveEntry> list = panel.reviewList();
          for (int index : new int[] {0, 1, 0}) {
            JPanel cell =
                (JPanel)
                    list.getCellRenderer()
                        .getListCellRendererComponent(
                            list, list.getModel().getElementAt(index), index, false, false);
            JTextArea caption = (JTextArea) cell.getComponent(4);
            assertEquals("胜率损失 " + (index == 0 ? "32.8%" : "8.5%"), caption.getText());
          }
        });
  }

  private static BlunderListPanel panel() {
    return new BlunderListPanel(e -> {}, () -> ProblemListSideFilter.BLACK);
  }

  private static List<Integer> moves(BlunderListPanel panel) {
    List<Integer> moves = new ArrayList<>();
    for (int i = 0; i < panel.reviewList().getModel().getSize(); i++)
      moves.add(panel.reviewList().getModel().getElementAt(i).moveNumber);
    return moves;
  }

  private static ProblemMoveEntry row(int move, double loss) {
    return new ProblemMoveEntry(true, move, "D4", loss, 0, false, 1200, false, 3);
  }

  private static ProblemMoveEntry white(int move, double loss) {
    return new ProblemMoveEntry(false, move, "Q16", loss, 0, false, 1200, false, 3);
  }

  private static ProblemListSnapshot snapshot(ProblemMoveEntry... entries) {
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS, List.of(entries), List.of(), 100, 100, false);
  }

  private static void click(JList<?> list, int x, int y) {
    send(list, MouseEvent.MOUSE_PRESSED, x, y);
    send(list, MouseEvent.MOUSE_RELEASED, x, y);
  }

  private static void send(JList<?> list, int event, int x, int y) {
    MouseEvent mouse =
        new MouseEvent(
            list, event, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1);
    // BasicListUI queries native menu modifiers, which are unavailable in headless tests.
    // Exercise our hit/identity listener here; the native acceptance covers actual dispatch.
    for (MouseListener listener : list.getMouseListeners()) {
      if (listener.getClass().getEnclosingClass() == BlunderListPanel.class) {
        if (event == MouseEvent.MOUSE_PRESSED) listener.mousePressed(mouse);
        if (event == MouseEvent.MOUSE_RELEASED) listener.mouseReleased(mouse);
      }
    }
  }

  private static void edt(Runnable operation) throws Exception {
    SwingUtilities.invokeAndWait(operation);
  }
}
