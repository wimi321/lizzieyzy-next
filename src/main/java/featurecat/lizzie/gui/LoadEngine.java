package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableColumn;

public class LoadEngine extends JPanel {
  public static Config config;
  public TableModel dataModel;
  JPanel tablepanel;
  PanelWithToolTips selectpanel = new PanelWithToolTips();

  JScrollPane scrollpane;
  public static boolean noShow = false;
  public static JTable table;
  public static JFontLabel checkBlacktxt;
  public static JFontLabel checkWhitetxt;
  Font headFont;
  Font winrateFont;
  static JDialog engjf;
  // Timer timer;
  int sortnum = 3;
  public static int selectedorder = -1;
  boolean issorted = false;
  boolean firstConf = true;
  // JSpinner dropwinratechooser = new JSpinner(new SpinnerNumberModel(1, 0, 99,
  // 1));
  // JSpinner playoutschooser = new JSpinner(new SpinnerNumberModel(100, 0, 99999,
  // 100));
  // JCheckBox checkBlack = new JCheckBox();
  // JCheckBox checkWhite = new JCheckBox();
  // JTextArea command;
  // JTextField txtName;
  // JFontLabel engineName;
  // JCheckBox preload;
  // JTextField txtWidth;
  // JTextField txtHeight;
  // JTextField txtKomi;
  //
  // JFontButton scan;
  // JFontButton delete;
  JFontButton ok;
  JFontButton noEngine;
  JFontButton exit;
  // JCheckBox chkdefault;
  JRadioButton rdoDefault;
  JRadioButton rdoLast;
  JRadioButton rdoMannul;
  JRadioButton rdoNone;
  int curIndex = -1;

  public String enginePath = "";
  public String weightPath = "";
  public String commandHelp = "";
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  public LoadEngine() {
    // super(new BorderLayout());

    (new File("")).getAbsoluteFile().toPath();
    System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
    this.setLayout(null);
    dataModel = getTableModel();
    table = new JTable(dataModel);
    selectpanel.setLayout(new BorderLayout(8, 2));
    winrateFont =
        new Font(Config.sysDefaultFontName, Font.PLAIN, Math.max(Config.frameFontSize, 14));
    headFont = new Font(Config.sysDefaultFontName, Font.PLAIN, Math.max(Config.frameFontSize, 13));

    table.getTableHeader().setFont(headFont);
    table.setFont(winrateFont);
    table.getTableHeader().setReorderingAllowed(false);
    table.getTableHeader().setResizingAllowed(false);
    TableCellRenderer tcr = new ColorTableCellRenderer();
    table.setDefaultRenderer(Object.class, tcr);
    table.setRowHeight(Config.menuHeight);
    table.getTableHeader().setFont(headFont);
    table
        .getTableHeader()
        .setPreferredSize(
            new Dimension(
                table.getColumnModel().getTotalColumnWidth(),
                Lizzie.config.isFrameFontSmall()
                    ? 28
                    : (Lizzie.config.isFrameFontMiddle() ? 30 : 38)));
    table.setFont(winrateFont);

    tablepanel = new JPanel(new BorderLayout());
    tablepanel.setBounds(0, 0, 895, 429);
    this.add(tablepanel);
    selectpanel.setBounds(0, 432, 895, 70);
    this.add(selectpanel);
    scrollpane = new JScrollPane(table);

    tablepanel.add(scrollpane);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    fitColumnsToLocalizedHeaders(
        table,
        new int[] {
          Lizzie.config.isFrameFontSmall()
              ? 30
              : (Lizzie.config.isFrameFontMiddle() ? 35 : 40),
          220,
          300,
          Lizzie.config.isFrameFontSmall()
              ? 48
              : (Lizzie.config.isFrameFontMiddle() ? 52 : 60),
          36,
          36,
          Lizzie.config.isFrameFontSmall()
              ? 42
              : (Lizzie.config.isFrameFontMiddle() ? 46 : 52),
          Lizzie.config.isFrameFontSmall()
              ? 48
              : (Lizzie.config.isFrameFontMiddle() ? 52 : 60)
        });
    // boolean persisted = Lizzie.config.persistedUi != null;
    // if (persisted
    // && Lizzie.config.persistedUi.optJSONArray("badmoves-list-position") != null
    // && Lizzie.config.persistedUi.optJSONArray("badmoves-list-position").length()
    // == 12) {
    // JSONArray pos =
    // Lizzie.config.persistedUi.getJSONArray("badmoves-list-position");
    // // table.getColumnModel().getColumn(0).setPreferredWidth(pos.getInt(4));
    // // table.getColumnModel().getColumn(1).setPreferredWidth(pos.getInt(5));
    // // table.getColumnModel().getColumn(2).setPreferredWidth(pos.getInt(6));
    // // table.getColumnModel().getColumn(3).setPreferredWidth(pos.getInt(7));
    // }

    JTableHeader header = table.getTableHeader();

    // dropwinratechooser.setValue(Lizzie.config.limitbadmoves);
    // playoutschooser.setValue(Lizzie.config.limitbadplayouts);
    // checkBlack.setSelected(true);
    // checkWhite.setSelected(true);

    ok = new JFontButton(resourceBundle.getString("loadEngine.ok")); // "加载选中引擎");
    noEngine = new JFontButton(resourceBundle.getString("loadEngine.noEngine")); // ("不加载引擎");
    exit = new JFontButton(resourceBundle.getString("loadEngine.exit")); // ("退出");

    noEngine.setFocusable(true);
    noEngine.setMargin(new Insets(3, 14, 3, 14));
    exit.setFocusable(true);
    exit.setMargin(new Insets(3, 14, 3, 14));
    ok.setFocusable(true);
    ok.setMargin(new Insets(3, 14, 3, 14));

    JFontLabel lblchooseStart =
        new JFontLabel(resourceBundle.getString("ChooseMoreEngine.lblchooseStart")); // ("每次启动：");
    rdoDefault = new JFontRadioButton(resourceBundle.getString("ChooseMoreEngine.lblrdoDefault"));
    rdoLast = new JFontRadioButton(resourceBundle.getString("ChooseMoreEngine.lblrdoLast"));
    rdoMannul = new JFontRadioButton(resourceBundle.getString("ChooseMoreEngine.lblrdoMannul"));
    rdoNone = new JFontRadioButton(resourceBundle.getString("ChooseMoreEngine.lblrdoNone"));
    if (Lizzie.config.uiConfig.optBoolean("autoload-default", false)) rdoDefault.setSelected(true);
    else if (Lizzie.config.uiConfig.optBoolean("autoload-last", false)) rdoLast.setSelected(true);
    else if (Lizzie.config.uiConfig.optBoolean("autoload-empty", false)) rdoNone.setSelected(true);
    else rdoMannul.setSelected(true);

    ButtonGroup startGroup = new ButtonGroup();
    startGroup.add(rdoDefault);
    startGroup.add(rdoLast);
    startGroup.add(rdoMannul);
    startGroup.add(rdoNone);

    JPanel startupModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
    startupModePanel.setOpaque(false);
    startupModePanel.add(lblchooseStart);
    startupModePanel.add(rdoDefault);
    startupModePanel.add(rdoLast);
    startupModePanel.add(rdoMannul);
    startupModePanel.add(rdoNone);

    JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
    actionPanel.setOpaque(false);
    actionPanel.add(ok);
    actionPanel.add(noEngine);
    actionPanel.add(exit);

    selectpanel.add(startupModePanel, BorderLayout.CENTER);
    selectpanel.add(actionPanel, BorderLayout.SOUTH);

    ok.addActionListener(
        new ActionListener() {

          @Override
          public void actionPerformed(ActionEvent e) {
            curIndex = table.getSelectedRow();
            if (curIndex < 0) {
              JOptionPane.showMessageDialog(
                  engjf, resourceBundle.getString("loadEngine.selectPrompt"));
              return;
            }
            engjf.setVisible(false);
            if (rdoDefault.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-default", true);
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoLast.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", true);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoMannul.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoNone.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", true);
            }
            Lizzie.start(curIndex, false);
          }
        });
    exit.addActionListener(
        new ActionListener() {

          @Override
          public void actionPerformed(ActionEvent e) {
            // TODO Auto-generated method stub
            System.exit(0);
          }
        });
    noEngine.addActionListener(
        new ActionListener() {

          @Override
          public void actionPerformed(ActionEvent e) {
            engjf.setVisible(false);
            if (rdoDefault.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-default", true);
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoLast.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", true);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoMannul.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", false);
            } else if (rdoNone.isSelected()) {
              Lizzie.config.uiConfig.put("autoload-last", false);
              Lizzie.config.uiConfig.put("autoload-default", false);
              Lizzie.config.uiConfig.put("autoload-empty", true);
            }
            Lizzie.start(-1, false);
          }
        });

    table.addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int col = table.columnAtPoint(e.getPoint());
            if (e.getClickCount() == 2) {
              if (row >= 0 && col >= 0) {
                try {
                  handleTableDoubleClick(row, col);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
              }
            } else {
              try {
                handleTableClick(row, col);
              } catch (Exception ex) {
                ex.printStackTrace();
              }
            }
          }
        });
    table.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            // if (e.getKeyCode() == KeyEvent.VK_B) {
            // Lizzie.frame.toggleBadMoves();
            // }
            // if (e.getKeyCode() == KeyEvent.VK_U) {
            // Lizzie.frame.toggleBestMoves();
            // }
            // if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            // if (Lizzie.frame.isPlayingAgainstLeelaz) {
            // Lizzie.frame.isPlayingAgainstLeelaz = false;
            // Lizzie.leelaz.isThinking = false;
            // }
            // Lizzie.leelaz.togglePonder();
            // }
            // if (e.getKeyCode() == KeyEvent.VK_Q) {
            // togglealwaysontop();
            // }
          }
        });

    header.addMouseListener(
        new MouseAdapter() {
          public void mouseReleased(MouseEvent e) {
            // int pick = header.columnAtPoint(e.getPoint());
            // sortnum = pick;
            // issorted = !issorted;
          }
        });
  }

  static void fitColumnsToLocalizedHeaders(JTable target, int[] preferredWidths) {
    if (target == null || preferredWidths == null) {
      return;
    }
    JTableHeader header = target.getTableHeader();
    TableCellRenderer renderer = header.getDefaultRenderer();
    int columnCount = Math.min(target.getColumnCount(), preferredWidths.length);
    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
      TableColumn column = target.getColumnModel().getColumn(columnIndex);
      Component headerComponent =
          renderer.getTableCellRendererComponent(
              target, column.getHeaderValue(), false, false, -1, columnIndex);
      int headerWidth = headerComponent.getPreferredSize().width + 16;
      int preferredWidth = Math.max(preferredWidths[columnIndex], headerWidth);
      column.setMinWidth(headerWidth);
      column.setPreferredWidth(preferredWidth);
    }
  }

  class ColorTableCellRenderer extends DefaultTableCellRenderer {
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      {
        if (column == 2) {
          JLabel label =
              (JLabel)
                  super.getTableCellRendererComponent(
                      table, value, isSelected, hasFocus, row, column);
          label.setToolTipText(value.toString());
          return label;
        }
        return renderer.getTableCellRendererComponent(table, value, isSelected, false, row, column);
      }
    }
  }

  private void handleTableClick(int row, int col) {
    if (row < 0) return;
    curIndex = Integer.parseInt(table.getModel().getValueAt(row, 0).toString()) - 1;
  }

  private void handleTableDoubleClick(int row, int col) {
    engjf.setVisible(false);
    curIndex = Integer.parseInt(table.getModel().getValueAt(row, 0).toString()) - 1;
    if (rdoDefault.isSelected()) {
      Lizzie.config.uiConfig.put("autoload-default", true);
      Lizzie.config.uiConfig.put("autoload-last", false);
      Lizzie.config.uiConfig.put("autoload-empty", false);
    } else if (rdoLast.isSelected()) {
      Lizzie.config.uiConfig.put("autoload-last", true);
      Lizzie.config.uiConfig.put("autoload-default", false);
      Lizzie.config.uiConfig.put("autoload-empty", false);
    } else if (rdoMannul.isSelected()) {
      Lizzie.config.uiConfig.put("autoload-last", false);
      Lizzie.config.uiConfig.put("autoload-default", false);
      Lizzie.config.uiConfig.put("autoload-empty", false);
    } else if (rdoNone.isSelected()) {
      Lizzie.config.uiConfig.put("autoload-last", false);
      Lizzie.config.uiConfig.put("autoload-default", false);
      Lizzie.config.uiConfig.put("autoload-empty", true);
    }
    Lizzie.start(curIndex, false);
  }

  //  private ArrayList<EngineData> getEngineData() {
  //    ArrayList<EngineData> engineData = new ArrayList<EngineData>();
  //    Optional<JSONArray> enginesCommandOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-command-list"));
  //    Optional<JSONArray> enginesNameOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-name-list"));
  //    Optional<JSONArray> enginesPreloadOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-preload-list"));
  //
  //    Optional<JSONArray> enginesWidthOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-width-list"));
  //
  //    Optional<JSONArray> enginesHeightOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-height-list"));
  //    Optional<JSONArray> enginesKomiOpt =
  //        Optional.ofNullable(Lizzie.config.leelazConfig.optJSONArray("engine-komi-list"));
  //
  //    int defaultEngine = Lizzie.config.uiConfig.optInt("default-engine", -1);
  //
  //    for (int i = 0;
  //        i < (enginesCommandOpt.isPresent() ? enginesCommandOpt.get().length() + 1 : 0);
  //        i++) {
  //      if (i == 0) {
  //        String engineCommand = Lizzie.config.leelazConfig.getString("engine-command");
  //        int width = enginesWidthOpt.isPresent() ? enginesWidthOpt.get().optInt(i, 19) : 19;
  //        int height = enginesHeightOpt.isPresent() ? enginesHeightOpt.get().optInt(i, 19) : 19;
  //        String name = enginesNameOpt.isPresent() ? enginesNameOpt.get().optString(i, "") : "";
  //        float komi =
  //            enginesKomiOpt.isPresent()
  //                ? enginesKomiOpt.get().optFloat(i, (float) 7.5)
  //                : (float) 7.5;
  //        boolean preload =
  //            enginesPreloadOpt.isPresent() ? enginesPreloadOpt.get().optBoolean(i, false) :
  // false;
  //        EngineData enginedt = new EngineData();
  //        enginedt.commands = engineCommand;
  //        enginedt.name = name;
  //        enginedt.preload = preload;
  //        enginedt.index = i;
  //        enginedt.width = width;
  //        enginedt.height = height;
  //        enginedt.komi = komi;
  //        if (defaultEngine == i) enginedt.isDefault = true;
  //        else enginedt.isDefault = false;
  //        engineData.add(enginedt);
  //      } else {
  //        String commands =
  //            enginesCommandOpt.isPresent() ? enginesCommandOpt.get().optString(i - 1, "") : "";
  //        if (!commands.equals("")) {
  //          int width = enginesWidthOpt.isPresent() ? enginesWidthOpt.get().optInt(i, 19) : 19;
  //          int height = enginesHeightOpt.isPresent() ? enginesHeightOpt.get().optInt(i, 19) : 19;
  //          String name = enginesNameOpt.isPresent() ? enginesNameOpt.get().optString(i, "") : "";
  //          float komi =
  //              enginesKomiOpt.isPresent()
  //                  ? enginesKomiOpt.get().optFloat(i, (float) 7.5)
  //                  : (float) 7.5;
  //          boolean preload =
  //              enginesPreloadOpt.isPresent() ? enginesPreloadOpt.get().optBoolean(i, false) :
  // false;
  //          EngineData enginedt = new EngineData();
  //          enginedt.commands = commands;
  //          enginedt.name = name;
  //          enginedt.preload = preload;
  //          enginedt.index = i;
  //          enginedt.width = width;
  //          enginedt.height = height;
  //          enginedt.komi = komi;
  //          if (defaultEngine == i) enginedt.isDefault = true;
  //          else enginedt.isDefault = false;
  //          engineData.add(enginedt);
  //        }
  //      }
  //    }
  //
  //    return engineData;
  //  }

  public AbstractTableModel getTableModel() {

    return new AbstractTableModel() {
      public int getColumnCount() {

        return 8;
      }

      public int getRowCount() {
        ArrayList<EngineData> EngineDatas = Utils.getEngineData();
        return EngineDatas.size();
      }

      public String getColumnName(int column) {

        if (column == 0) return resourceBundle.getString("loadEngine.column0"); // "序号";
        if (column == 1) return resourceBundle.getString("loadEngine.column1"); // "名称";
        if (column == 2) return resourceBundle.getString("loadEngine.column2"); // "命令行";
        if (column == 3) return resourceBundle.getString("loadEngine.column3"); // "预加载";
        if (column == 4) return resourceBundle.getString("loadEngine.column4"); // "宽";
        if (column == 5) return resourceBundle.getString("loadEngine.column5"); // "高";
        if (column == 6) return resourceBundle.getString("loadEngine.column6"); // "贴目";
        if (column == 7) return resourceBundle.getString("loadEngine.column7"); // "默认";

        return "";
      }

      public Object getValueAt(int row, int col) {
        ArrayList<EngineData> EngineDatas = Utils.getEngineData();
        if (row > (EngineDatas.size() - 1)) {
          if (col == 0) return row + 1;
          return "";
        }
        EngineData data = EngineDatas.get(row);

        if (col != 0 && data.commands.equals("")) {
          return "";
        }

        switch (col) {
          case 0:
            return data.index + 1;
          case 1:
            return data.name;
          case 2:
            return data.commands;
          case 3:
            if (data.preload) return resourceBundle.getString("loadEngine.yes");
            return resourceBundle.getString("loadEngine.no");
          case 4:
            return data.width;
          case 5:
            return data.height;
          case 6:
            return data.komi;
          case 7:
            if (data.isDefault) return resourceBundle.getString("loadEngine.yes");
            else return resourceBundle.getString("loadEngine.no");
          default:
            return "";
        }
      }
    };
  }

  public static JDialog createDialog() {
    // Create and set up the window.
    engjf = new JDialog();
    engjf.setTitle(Lizzie.resourceBundle.getString("loadEngine.title")); // "选择要加载的引擎(双击加载)");
    engjf.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            engjf.setVisible(false);
          }
        });

    final LoadEngine newContentPane = new LoadEngine();
    newContentPane.setOpaque(true); // content panes must be opaque
    engjf.setContentPane(newContentPane);
    AccessibilitySupport.named(
        newContentPane.table,
        Lizzie.resourceBundle.getString("loadEngine.title"),
        Lizzie.resourceBundle.getString("loadEngine.title"));
    AccessibilitySupport.applyToTree(newContentPane);
    AccessibilitySupport.installEscapeAction(engjf.getRootPane(), engjf, () -> System.exit(0));
    engjf.setModal(true);
    // Display the window.
    // jf.setSize(521, 320);

    // boolean persisted = Lizzie.config.persistedUi != null;
    engjf.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            System.exit(0);
          }
        });

    engjf.setBounds(50, 50, 900, 540);
    Lizzie.setFrameSize(engjf, 900, 540);
    engjf.setResizable(false);
    try {
      engjf.setIconImage(ImageIO.read(LoadEngine.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    engjf.setAlwaysOnTop(true);
    engjf.setLocationRelativeTo(engjf.getOwner());
    LizzieFrame.constrainWindowToAvailableWorkArea(engjf);
    // jf.setResizable(false);
    return engjf;
  }
}
