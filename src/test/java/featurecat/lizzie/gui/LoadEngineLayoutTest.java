package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.junit.jupiter.api.Test;

class LoadEngineLayoutTest {
  @Test
  void localizedHeadersCannotBeCompressedBelowTheirRenderedWidth() {
    String[] headers = {
      "ลำดับ",
      "ชื่อ",
      "คำสั่งเริ่มต้น",
      "โหลดล่วงหน้า",
      "กว้าง",
      "สูง",
      "โคมิ",
      "ค่าเริ่มต้น"
    };
    JTable table = new JTable(new DefaultTableModel(headers, 0));

    LoadEngine.fitColumnsToLocalizedHeaders(
        table, new int[] {30, 120, 240, 40, 20, 20, 30, 30});

    TableCellRenderer renderer = table.getTableHeader().getDefaultRenderer();
    for (int columnIndex = 0; columnIndex < table.getColumnCount(); columnIndex++) {
      TableColumn column = table.getColumnModel().getColumn(columnIndex);
      Component rendered =
          renderer.getTableCellRendererComponent(
              table, column.getHeaderValue(), false, false, -1, columnIndex);
      assertTrue(
          column.getMinWidth() >= rendered.getPreferredSize().width,
          "column " + columnIndex + " must fit its localized header");
    }
  }
}
