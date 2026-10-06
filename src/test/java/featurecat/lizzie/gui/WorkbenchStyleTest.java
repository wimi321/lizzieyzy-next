package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchStyleTest {
  @TempDir Path tempDir;

  @Test
  void settingsNavigationHasOneClickableSurfaceAndKeyboardActions() throws Exception {
    Class<?> type = Class.forName("featurecat.lizzie.gui.ConfigDialog2$ModernTabComponent");
    java.lang.reflect.Constructor<?> constructor =
        type.getDeclaredConstructor(String.class, String.class, String.class);
    constructor.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            JButton button =
                (JButton) constructor.newInstance("Theme", "Fonts and colors", "theme");
            button.setSize(button.getPreferredSize());
            button.doLayout();
            for (java.awt.Component child : button.getComponents()) {
              if (child instanceof java.awt.Container) ((java.awt.Container) child).doLayout();
              assertSame(
                  button,
                  SwingUtilities.getDeepestComponentAt(
                      button,
                      child.getX() + child.getWidth() / 2,
                      child.getY() + child.getHeight() / 2));
            }
            assertTrue(button.isFocusable());
            assertEquals(
                javax.accessibility.AccessibleRole.PUSH_BUTTON,
                button.getAccessibleContext().getAccessibleRole());
            assertEquals("Theme", button.getAccessibleContext().getAccessibleName());
            java.util.concurrent.atomic.AtomicInteger clicks =
                new java.util.concurrent.atomic.AtomicInteger();
            button.addActionListener(e -> clicks.incrementAndGet());
            Object enter = button.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
            button
                .getActionMap()
                .get(enter)
                .actionPerformed(new java.awt.event.ActionEvent(button, 0, ""));
            for (String key : new String[] {"pressed SPACE", "released SPACE"}) {
              Object action = button.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(key));
              assertNotNull(action, key);
              button
                  .getActionMap()
                  .get(action)
                  .actionPerformed(new java.awt.event.ActionEvent(button, 0, ""));
            }
            assertEquals(2, clicks.get());
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });
  }

  @Test
  void textAndSecondaryTextRemainReadableAcrossThemes() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiConfig = new JSONObject();
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        for (Color surface :
            new Color[] {
              AppleStyleSupport.workspaceSurface(), AppleStyleSupport.workspaceBackground()
            }) {
          assertTrue(contrast(AppleStyleSupport.dialogTextColor(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceMuted(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceSuccess(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceWarning(), surface) >= 4.5);
          assertTrue(contrast(AppleStyleSupport.workspaceError(), surface) >= 4.5);
        }
        assertTrue(
            contrast(AppleStyleSupport.dialogTextColor(), AppleStyleSupport.workspaceSelection())
                >= 4.5);
        assertTrue(
            contrast(AppleStyleSupport.workspaceMuted(), AppleStyleSupport.workspaceSelection())
                >= 4.5);
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void toolbarSurfaceRemainsReadableWithoutMorandiPalette() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.useMorandiColors = false;
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        BufferedImage image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 40);
        AppleStyleSupport.paintToolbarSurface(g, 300, 40, false);
        g.dispose();
        Color surface = new Color(image.getRGB(150, 20));
        JButton button = new JButton("Last move");
        AppleStyleSupport.installButtonStyle(button);
        assertTrue(contrast(button.getForeground(), surface) >= 4.5);
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void disabledComboKeepsThemedSurfaceAndReadableLabel() throws Exception {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        SwingUtilities.invokeAndWait(
            () -> {
              AppleStyleSupport.applyUiDefaults();
              JComboBox<String> combo = new JComboBox<>(new String[] {"Transformer B11"});
              AppleStyleSupport.installComboBoxStyle(combo);
              combo.setEnabled(false);
              combo.setSize(300, 40);
              combo.doLayout();
              BufferedImage image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB);
              Graphics2D g = image.createGraphics();
              combo.paint(g);
              g.dispose();
              Color background = new Color(image.getRGB(220, 20));
              assertEquals(combo.getBackground(), background);
              assertTrue(contrast(AppleStyleSupport.workspaceMuted(), background) >= 4.5);
            });
      }
    } finally {
      Lizzie.config = previous;
      AppleStyleSupport.applyUiDefaults();
    }
  }

  @Test
  void fontResolutionToleratesStartupBeforeUiConfiguration() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiConfig = null;
      config.uiFontName = Config.sysDefaultFontName;
      Lizzie.config = config;
      assertNotNull(AppleStyleSupport.workspaceFont(Font.PLAIN, 13));
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void readOnlySettingsTextRetainsWrappingAndNeverLooksEditable() throws Exception {
    java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    ConfigDialog2 dialog =
        (ConfigDialog2) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ConfigDialog2.class);
    java.lang.reflect.Method create =
        ConfigDialog2.class.getDeclaredMethod("createSettingText", String.class, boolean.class);
    java.lang.reflect.Method style =
        ConfigDialog2.class.getDeclaredMethod("modernizeComponentTree", java.awt.Component.class);
    create.setAccessible(true);
    style.setAccessible(true);
    JTextArea text = (JTextArea) create.invoke(dialog, "A long setting description", true);
    java.awt.Insets before = text.getInsets();
    style.invoke(dialog, text);
    assertEquals(before, text.getInsets());
    assertFalse(text.isOpaque());
    assertFalse(text.isEditable());
    assertTrue(text.getLineWrap());
    assertEquals(text.getText(), text.getAccessibleContext().getAccessibleName());
  }

  @Test
  void primaryActionsKeepLegibleTextAfterGlobalThemeRefresh() {
    Config previous = Lizzie.config;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      for (boolean dark : new boolean[] {false, true}) {
        config.isAppleStyle = dark;
        JButton button = new JButton("Save settings");
        AppleStyleSupport.preserveCustomButtonStyle(button);
        HumanSlTrainingStyle.stylePrimary(button);
        Dimension size = button.getPreferredSize();
        AppleStyleSupport.installButtonStyle(button);
        assertEquals(size, button.getPreferredSize());
        assertTrue(contrast(button.getForeground(), button.getBackground()) >= 4.5);
        button.setSize(size);
        BufferedImage image =
            new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        button.paint(graphics);
        graphics.dispose();
        assertEquals(button.getBackground().getRGB(), image.getRGB(size.width / 2, 5));
      }
    } finally {
      Lizzie.config = previous;
    }
  }

  @Test
  void suggestionTableRefreshKeepsDataAndFitsConfiguredFont() throws Exception {
    Config previous = Lizzie.config;
    int previousSize = Config.frameFontSize;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config = config;
      Config.frameFontSize = 22;
      java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      LizzieFrame frame =
          (LizzieFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LizzieFrame.class);
      Lizzie.frame = frame;
      SwingUtilities.invokeAndWait(
          () -> {
            Object[][] data = {
              {"1", "D4", "55.2", "1200", "42.5", "+0.5"},
              {"2", "Q16", "53.1", "980", "35.0", "-0.2"},
              {"3(actual)", "R17", "↓1.5(51.0)", "620", "22.5", "↓0.5(+0.1)"}
            };
            frame.listTable =
                new JTable(
                    data, new String[] {"Move", "Coords", "Winrate", "Visits", "Share", "Score"});
            JTable table = frame.listTable;
            TableCellRenderer renderer = frame.new ColorTableCellRenderer();
            table.setDefaultRenderer(Object.class, renderer);
            for (int column = 0; column < table.getColumnCount(); column++) {
              table.getColumnModel().getColumn(column).setCellRenderer(renderer);
              DefaultTableCellRenderer header = new DefaultTableCellRenderer();
              header.setHorizontalAlignment(SwingConstants.CENTER);
              table.getColumnModel().getColumn(column).setHeaderRenderer(header);
            }
            frame.listScrollpane = new SuggestionTableScrollPane(table);
            frame.listScrollpane.setColumnHeaderView(table.getTableHeader());
            for (boolean dark : new boolean[] {false, true}) {
              config.isAppleStyle = dark;
              frame.refreshSuggestionTableStyle();
              assertEquals(22, table.getFont().getSize());
              assertEquals(Font.PLAIN, table.getFont().getStyle());
              assertEquals(
                  AppleStyleSupport.workspaceSurface(),
                  frame.listScrollpane.getViewport().getBackground());
              assertTrue(contrast(table.getForeground(), table.getBackground()) >= 4.5);
              frame.listScrollpane.setSize(900, 300);
              frame.listScrollpane.doLayout();
              JTableHeader header = table.getTableHeader();
              int headerHeight = header.getPreferredSize().height;
              int baseline =
                  Math.max(
                      Config.menuHeight - 4, table.getFontMetrics(table.getFont()).getHeight() + 8);
              int chrome =
                  frame.listScrollpane.getViewport().getY()
                      + frame.listScrollpane.getInsets().bottom;
              for (int budget :
                  new int[] {
                    baseline * 3,
                    (baseline - 1) * 3,
                    baseline * 3 - 1,
                    baseline * 3 + baseline / 2,
                    baseline - 3,
                    0,
                    1 - headerHeight
                  }) {
                frame.listScrollpane.setSize(900, chrome + budget);
                frame.listScrollpane.doLayout();
                table.doLayout();
                int extent = frame.listScrollpane.getViewport().getHeight();
                assertTrue(table.getRowHeight() > 0);
                assertEquals(0, extent % table.getRowHeight());
                assertTrue(extent <= Math.max(0, budget));
                if (budget == baseline * 3 || budget == (baseline - 1) * 3) {
                  assertEquals(budget / 3, table.getRowHeight());
                }
                if (budget < baseline - 2) {
                  assertEquals(0, extent);
                  assertEquals(0, table.getHeight());
                  assertFalse(frame.listScrollpane.isWheelScrollingEnabled());
                  assertFalse(frame.listScrollpane.getVerticalScrollBar().isEnabled());
                }
                assertEquals(
                    budget < 0 ? 0 : headerHeight,
                    frame.listScrollpane.getColumnHeader().getHeight());
                assertTrue(frame.listScrollpane.getVerticalScrollBar().getMaximum() >= 0);
                for (int state = 0; state < 3; state++) {
                  frame.suggestionclick =
                      state == 0
                          ? LizzieFrame.outOfBoundCoordinate
                          : Board.convertNameToCoordinates("D4");
                  frame.selectedorder = state == 2 ? 1 : -1;
                  for (int row = 0; row < data.length; row++) {
                    for (int column = 0; column < table.getColumnCount(); column++) {
                      assertEquals(data[row][column], table.getValueAt(row, column));
                      Component cell =
                          renderer.getTableCellRendererComponent(
                              table, table.getValueAt(row, column), false, false, row, column);
                      assertEquals(22, cell.getFont().getSize());
                      assertRendererContentFits(
                          cell, 240, table.getRowHeight() - table.getRowMargin(), 2);
                    }
                  }
                }
                for (int column = 0; column < table.getColumnCount(); column++) {
                  TableColumn col = header.getColumnModel().getColumn(column);
                  Component cell =
                      col.getHeaderRenderer()
                          .getTableCellRendererComponent(
                              table, col.getHeaderValue(), false, false, -1, column);
                  assertRendererContentFits(cell, 150, headerHeight, 0);
                  assertTrue(contrast(cell.getForeground(), cell.getBackground()) >= 4.5);
                }
              }
            }
          });
    } finally {
      Lizzie.config = previous;
      Config.frameFontSize = previousSize;
      Lizzie.frame = previousFrame;
    }
  }

  private static void assertRendererContentFits(
      Component renderer, int width, int height, int padding) {
    renderer.setSize(width, height);
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    renderer.paint(graphics);
    graphics.dispose();
    Insets insets = ((JComponent) renderer).getInsets();
    assertTrue(
        height - insets.top - insets.bottom
            >= renderer.getFontMetrics(renderer.getFont()).getHeight() + padding,
        "Actual renderer content needs vertical insets and safe text padding");
  }

  @Test
  void configuredFontAndLargerTextArePreserved() {
    Config previous = Lizzie.config;
    int previousSize = Config.frameFontSize;
    try {
      Config config = ConfigTestHelper.createForTests(tempDir);
      config.uiFontName = Font.MONOSPACED;
      Lizzie.config = config;
      Config.frameFontSize = 22;
      Font font = AppleStyleSupport.workspaceFont(Font.BOLD, 12);
      assertEquals(22, font.getSize());
      assertEquals(Font.MONOSPACED, font.getFamily());
    } finally {
      Lizzie.config = previous;
      Config.frameFontSize = previousSize;
    }
  }

  @Test
  void sixLanguageRowsWrapWithoutLosingControlOrOverlapping() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (String tag : new String[] {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko", "th-TH"}) {
            ResourceBundle strings =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(tag));
            for (int size : new int[] {12, 18, 24}) {
              WorkbenchFormRow row = new WorkbenchFormRow();
              JPanel labels = new JPanel(new BorderLayout(0, 2));
              JTextArea text = new JTextArea(strings.getString("ConfigDialog2.modern.footerHint"));
              text.setLineWrap(true);
              text.setWrapStyleWord(true);
              text.setFont(new Font(Font.DIALOG, Font.PLAIN, size));
              labels.add(text);
              row.add(labels);
              JPanel controls = new JPanel();
              controls.setPreferredSize(new Dimension(240, 36));
              row.add(controls);
              for (int width : new int[] {360, 720, 1100}) {
                row.setSize(width, 400);
                row.setSize(width, row.getPreferredSize().height);
                row.doLayout();
                labels.doLayout();
                assertFalse(labels.getBounds().intersects(controls.getBounds()), tag);
                assertTrue(controls.getX() + controls.getWidth() <= width, tag);
                assertTrue(controls.getY() + controls.getHeight() <= row.getHeight(), tag);
                assertTrue(text.getHeight() >= text.getPreferredSize().height, tag);
                if (width >= 720) assertEquals(16, controls.getX() - labels.getWidth(), tag);
              }
            }
          }
        });
  }

  @Test
  void setupSidebarExposesRenderedSectionNames() throws Exception {
    Class<?> type = Class.forName("featurecat.lizzie.gui.KataGoAutoSetupDialog$SidebarNavRenderer");
    java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<String> renderer =
                (javax.swing.ListCellRenderer<String>) constructor.newInstance();
            javax.swing.JList<String> list = new javax.swing.JList<>();
            for (String name : new String[] {"Overview", "Weights", "Performance", "NVIDIA"}) {
              java.awt.Component cell =
                  renderer.getListCellRendererComponent(list, name, 0, true, true);
              assertEquals(name, cell.getAccessibleContext().getAccessibleName());
              assertEquals(
                  javax.accessibility.AccessibleRole.LABEL,
                  cell.getAccessibleContext().getAccessibleRole());
            }
          } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
          }
        });
  }

  @Test
  void weightButtonsMeasureTextAfterTheirFinalBorderIsInstalled() throws Exception {
    Class<?> style = Class.forName("featurecat.lizzie.gui.KataGoAutoSetupDialog$WeightButtonStyle");
    java.lang.reflect.Method method =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "styleWeightButton", JFontButton.class, style);
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            for (String tag : new String[] {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko-KR", "th-TH"}) {
              String caption =
                  java.util.ResourceBundle.getBundle(
                          "l10n.DisplayStrings", java.util.Locale.forLanguageTag(tag))
                      .getString("AutoSetup.downloadOnDemand");
              JFontButton button = new JFontButton(caption);
              button.setIcon(javax.swing.UIManager.getIcon("OptionPane.informationIcon"));
              button.setPreferredSize(new Dimension(90, 32));
              method.invoke(null, button, style.getEnumConstants()[0]);
              int contentWidth =
                  button.getFontMetrics(button.getFont()).stringWidth(caption)
                      + button.getIcon().getIconWidth()
                      + button.getIconTextGap()
                      + button.getInsets().left
                      + button.getInsets().right;
              assertTrue(button.getPreferredSize().width >= contentWidth, tag);
            }
          } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
          }
        });
  }

  @Test
  void trainingFieldsExposeTheirVisibleLabelsToScreenReaders() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JComboBox<String> mode = new JComboBox<>(new String[] {"Review after game"});
          javax.swing.JComponent row = NewHumanSlGameDialog.field("Training mode", mode);
          javax.swing.JLabel label = (javax.swing.JLabel) row.getComponent(0);
          assertSame(mode, label.getLabelFor());
          assertEquals("Training mode", mode.getAccessibleContext().getAccessibleName());
        });
  }

  @Test
  void paintedPreviewRetainsItsExplicitHeightInResponsiveRows() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          WorkbenchFormRow row = new WorkbenchFormRow();
          JPanel labels = new JPanel(new BorderLayout());
          labels.add(new JTextArea("Board preview"));
          JPanel preview = new JPanel();
          preview.setPreferredSize(new Dimension(220, 180));
          WorkbenchFormRow.prepareControls(preview);
          row.add(labels);
          row.add(preview);
          for (int width : new int[] {360, 720, 1100}) {
            row.setSize(width, 400);
            row.setSize(width, row.getPreferredSize().height);
            layoutTree(row);
            assertEquals(180, preview.getHeight());
            assertFalse(labels.getBounds().intersects(preview.getBounds()));
            assertChildrenFit(row);
          }
        });
  }

  @Test
  void nestedControlGroupsWrapAndRemainInsideTheRow() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          WorkbenchFormRow row = new WorkbenchFormRow();
          JPanel labels = new JPanel(new BorderLayout());
          labels.add(new JTextArea("Image and texture settings"));
          row.add(labels);
          JPanel host = new JPanel(new BorderLayout());
          JPanel group = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 4));
          for (int i = 0; i < 4; i++) {
            JButton button = new JButton("Asset " + i);
            button.setPreferredSize(new Dimension(160, 38));
            group.add(button);
          }
          WorkbenchFormRow.prepareControls(group);
          host.add(group);
          row.add(host);
          for (int width : new int[] {760, 360, 1100, 480}) {
            row.setSize(width, 500);
            row.setSize(width, row.getPreferredSize().height);
            layoutTree(row);
            assertFalse(labels.getBounds().intersects(host.getBounds()));
            assertChildrenFit(row);
            if (width == 360) assertTrue(group.getHeight() >= 2 * 38 + 12);
          }
        });
  }

  private static void layoutTree(java.awt.Container root) {
    root.doLayout();
    for (java.awt.Component child : root.getComponents()) {
      if (child instanceof java.awt.Container) layoutTree((java.awt.Container) child);
    }
  }

  private static void assertChildrenFit(java.awt.Container root) {
    for (java.awt.Component child : root.getComponents()) {
      assertTrue(child.getX() >= 0 && child.getY() >= 0, child.toString());
      assertTrue(child.getX() + child.getWidth() <= root.getWidth(), child.toString());
      assertTrue(child.getY() + child.getHeight() <= root.getHeight(), child.toString());
      if (child instanceof JPanel) assertChildrenFit((JPanel) child);
    }
  }

  @Test
  void keyboardFocusIsVisibleAndDoesNotChangeButtonGeometry() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JButton button =
              new JButton("Analyze") {
                @Override
                public boolean hasFocus() {
                  return true;
                }
              };
          button.setPreferredSize(new Dimension(120, 36));
          for (double scale : new double[] {1, 1.5, 2}) {
            AppleStyleSupport.installButtonStyle(button);
            assertEquals(new Dimension(120, 36), button.getPreferredSize());
            button.setSize(button.getPreferredSize());
            BufferedImage image =
                new BufferedImage(
                    (int) (120 * scale), (int) (36 * scale), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.scale(scale, scale);
            button.paint(g);
            g.dispose();
            assertEquals(
                AppleStyleSupport.workspaceAccent().getRGB(),
                image.getRGB((int) (60 * scale), (int) (2 * scale)));
          }
        });
  }

  private static double contrast(Color a, Color b) {
    double first = luminance(a), second = luminance(b);
    return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
  }

  private static double luminance(Color color) {
    return .2126 * channel(color.getRed())
        + .7152 * channel(color.getGreen())
        + .0722 * channel(color.getBlue());
  }

  private static double channel(int value) {
    double normalized = value / 255.0;
    return normalized <= .04045 ? normalized / 12.92 : Math.pow((normalized + .055) / 1.055, 2.4);
  }
}
