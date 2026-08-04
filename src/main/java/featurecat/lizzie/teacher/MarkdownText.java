package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent TeacherRunCardPro 的 MarkdownText：轻量 Markdown → HTML 渲染。
 * 支持：标题(#/##)、有序/无序列表、引用(>)、分隔线(---)、粗体(**)、行内代码(`)、链接([x](y))。 Swing 用 JEditorPane(text/html) 渲染输出
 * HTML。
 */
public final class MarkdownText {

  private MarkdownText() {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
  private static final Pattern QUOTE = Pattern.compile("^>\\s?(.+)$");
  private static final Pattern NUMBERED = Pattern.compile("^(\\d+)[.、]\\s*(.+)$");
  private static final Pattern BULLET = Pattern.compile("^[-*•]\\s+(.+)$");
  private static final Pattern HR = Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$");

  public static String toHtml(String text) {
    StringBuilder html =
        new StringBuilder(
            "<html><body style='font-family:SansSerif;font-size:13px;line-height:1.5'>");
    String[] lines = text.replace("\r\n", "\n").split("\n");
    final List<String> list = new ArrayList<>();
    final String[] listKind = {null};

    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty()) {
        flush(list, listKind, html);
        continue;
      }
      Matcher hr = HR.matcher(line);
      if (hr.matches()) {
        flush(list, listKind, html);
        html.append("<hr/>");
        continue;
      }
      Matcher h = HEADING.matcher(line);
      if (h.matches()) {
        flush(list, listKind, html);
        int lvl = h.group(1).length() <= 2 ? 3 : 4;
        html.append("<h")
            .append(lvl)
            .append(">")
            .append(inline(h.group(2)))
            .append("</h")
            .append(lvl)
            .append(">");
        continue;
      }
      Matcher q = QUOTE.matcher(line);
      if (q.matches()) {
        flush(list, listKind, html);
        html.append(
                "<blockquote style='border-left:3px solid #999;margin:4px 0;padding:2px 8px;color:#555'>")
            .append(inline(q.group(1)))
            .append("</blockquote>");
        continue;
      }
      Matcher n = NUMBERED.matcher(line);
      if (n.matches()) {
        if (listKind[0] != null && !listKind[0].equals("ol")) flush(list, listKind, html);
        listKind[0] = "ol";
        list.add(inline(n.group(2)));
        continue;
      }
      Matcher b = BULLET.matcher(line);
      if (b.matches()) {
        if (listKind[0] != null && !listKind[0].equals("ul")) flush(list, listKind, html);
        listKind[0] = "ul";
        list.add(inline(b.group(1)));
        continue;
      }
      flush(list, listKind, html);
      html.append("<p>").append(inline(line)).append("</p>");
    }
    flush(list, listKind, html);
    html.append("</body></html>");
    return html.toString();
  }

  private static void flush(List<String> list, String[] listKind, StringBuilder html) {
    if (list.isEmpty()) return;
    html.append(listKind[0].equals("ol") ? "<ol>" : "<ul>");
    for (String li : list) html.append("<li>").append(li).append("</li>");
    html.append(listKind[0].equals("ol") ? "</ol>" : "</ul>");
    list.clear();
    listKind[0] = null;
  }

  private static String inline(String s) {
    s = escapeHtml(s);
    s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
    s = s.replaceAll("`([^`]+)`", "<code style='background:#eee;padding:0 3px'>$1</code>");
    s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1");
    return s;
  }

  private static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
