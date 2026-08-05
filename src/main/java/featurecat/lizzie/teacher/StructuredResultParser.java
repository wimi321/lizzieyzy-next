package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 GoAgent teacher/structuredResultParser.ts：从 LLM 返回文本中解析结构化教学结果
 *（支持 ```json 围栏或首字符 { 的 JSON；字段：headline/summary/keyMistakes/correctThinking/drills/followupQuestions）。
 * 这比 `###` 标记更稳健。
 */
public final class StructuredResultParser {

    private StructuredResultParser() {}

    public static class KeyMistake {
        public Integer moveNumber;
        public String color, played, recommended, errorType, severity, evidence, explanation;
    }

    public static class StructuredTeacherResult {
        public String taskType = "current-move";
        public String headline, summary, markdown;
        public List<KeyMistake> keyMistakes = new ArrayList<>();
        public List<String> correctThinking = new ArrayList<>();
        public List<String> drills = new ArrayList<>();
        public List<String> followupQuestions = new ArrayList<>();
        public List<String> knowledgeCardIds = new ArrayList<>();
    }

    /** 从 LLM 文本解析结构化结果；解析失败返回基于原文的兜底结果 */
    public static StructuredTeacherResult parse(String text, String taskType) {
        ExtractedJson ej = extractJson(text);
        if (ej != null && ej.jsonText != null) {
            // 极简 JSON 对象解析（取顶层字符串/数组字段）
            SimpleJson obj = SimpleJson.parseObject(ej.jsonText);
            if (obj != null) {
                StructuredTeacherResult r = new StructuredTeacherResult();
                String tt = obj.getString("taskType");
                if ("current-move".equals(tt) || "full-game".equals(tt) || "recent-games".equals(tt) || "freeform".equals(tt) || "move-range".equals(tt)) r.taskType = tt;
                else r.taskType = taskType;
                r.headline = obj.getString("headline");
                r.summary = obj.getString("summary", firstLine(text));
                r.keyMistakes = obj.getMistakes("keyMistakes");
                r.correctThinking = obj.getStringArray("correctThinking");
                r.drills = obj.getStringArray("drills");
                r.followupQuestions = obj.getStringArray("followupQuestions");
                r.knowledgeCardIds = obj.getStringArray("knowledgeCardIds");
                String md = obj.getString("markdown");
                r.markdown = (md != null && !md.isEmpty()) ? md : buildMarkdown(r, ej.trailingText);
                return r;
            }
        }
        StructuredTeacherResult fallback = new StructuredTeacherResult();
        fallback.taskType = taskType;
        fallback.headline = firstLine(text);
        fallback.summary = firstLine(text);
        fallback.markdown = text;
        return fallback;
    }

    static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\n")) { String t = line.trim(); if (!t.isEmpty()) return t.replaceAll("^#{1,6}\\s*", "").replaceAll("^[-*]\\s*", ""); }
        return "";
    }

    static String buildMarkdown(StructuredTeacherResult r, String trailing) {
        List<String> lines = new ArrayList<>();
        if (r.headline != null && !r.headline.isEmpty()) lines.add(r.headline);
        if (r.summary != null && !r.summary.isEmpty()) { lines.add(""); lines.add(r.summary); }
        if (!r.keyMistakes.isEmpty()) {
            lines.add(""); lines.add("关键问题手：");
            for (KeyMistake m : r.keyMistakes.subList(0, Math.min(4, r.keyMistakes.size()))) {
                String move = m.moveNumber != null ? ("第 " + m.moveNumber + " 手") : "当前手";
                String change = "";
                List<String> parts = new ArrayList<>();
                if (m.played != null) parts.add("实战 " + m.played);
                if (m.recommended != null) parts.add("推荐 " + m.recommended);
                if (!parts.isEmpty()) change = "（" + String.join("，", parts) + "）";
                lines.add("- " + move + change + "：" + (m.explanation != null ? m.explanation : (m.evidence != null ? m.evidence : m.errorType)));
            }
        }
        if (!r.correctThinking.isEmpty()) { lines.add(""); lines.add("正确思路："); for (String s : r.correctThinking.subList(0, Math.min(4, r.correctThinking.size()))) lines.add("- " + s); }
        if (!r.drills.isEmpty()) { lines.add(""); lines.add("训练建议："); for (String s : r.drills.subList(0, Math.min(3, r.drills.size()))) lines.add("- " + s); }
        if (!r.followupQuestions.isEmpty()) { lines.add(""); lines.add("可以继续问："); for (String s : r.followupQuestions.subList(0, Math.min(3, r.followupQuestions.size()))) lines.add("- " + s); }
        if (trailing != null && !trailing.isEmpty()) lines.add(""); lines.add(trailing);
        return String.join("\n", lines).trim();
    }

    // ---- 提取 JSON ----
    static class ExtractedJson { String jsonText; String trailingText; }

    static ExtractedJson extractJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            // 尝试 ```json 围栏（对齐 TS：围栏标记是 goagent-grounding-json 连字符）
            var fence = java.util.regex.Pattern.compile("```(?:json|goagent-grounding-json|goagent_grounding_json)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (fence.find()) {
                String inner = fence.group(1).trim();
                if (inner.startsWith("{")) return splitJson(inner, trimmed.replace(fence.group(0), "").trim());
            }
            return null;
        }
        // 对齐 TS splitLeadingJsonObject：JSON 对象后的文本作为 trailingText
        return splitJson(trimmed, "");
    }

    static ExtractedJson splitJson(String json, String trailing) {
        int depth = 0, i = 0; boolean inStr = false, esc = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { i++; break; } }
        }
        ExtractedJson ej = new ExtractedJson();
        ej.jsonText = json.substring(0, i).trim();
        // 对齐 TS splitLeadingJsonObject：JSON 之后的文本保留为 trailingText
        String rest = i < json.length() ? json.substring(i).trim() : "";
        ej.trailingText = (!trailing.isEmpty()) ? trailing : rest;
        return ej;
    }

    // ---- 极简 JSON 对象访问（仅本工程需要的字段）----
    public static class SimpleJson {
        final String src;
        SimpleJson(String src) { this.src = src; }
        static SimpleJson parseObject(String s) {
            s = s.trim();
            if (!s.startsWith("{")) return null;
            return new SimpleJson(s);
        }
        String getString(String key) { return getString(key, null); }
        String getString(String key, String def) {
            String v = findStringField(key);
            return v != null ? v : def;
        }
        List<String> getStringArray(String key) {
            List<String> out = new ArrayList<>();
            int idx = src.indexOf("\"" + key + "\"");
            if (idx < 0) return out;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return out;
            // 找 [ 起始
            int arrStart = src.indexOf('[', colon);
            if (arrStart < 0) return out;
            int depth = 0; int i = arrStart; boolean inStr = false, esc = false;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (inStr) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
                if (c == '"') inStr = true;
                else if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) break; }
                else if (c == ',' && depth == 1 && !inStr) { /* element sep */ }
            }
            String arrBody = src.substring(arrStart + 1, i);
            // 提取值：字符串 or 数字
            var m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arrBody);
            while (m.find()) out.add(unescape(m.group(1)));
            return out;
        }
        List<KeyMistake> getMistakes(String key) {
            List<KeyMistake> out = new ArrayList<>();
            int idx = src.indexOf("\"" + key + "\"");
            if (idx < 0) return out;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return out;
            int arrStart = src.indexOf('[', colon);
            if (arrStart < 0) return out;
            int depth = 0, i = arrStart; boolean inStr = false, esc = false;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (inStr) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
                if (c == '"') inStr = true;
                else if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) break; }
            }
            String arrBody = src.substring(arrStart + 1, i);
            // 按顶层 { 切分对象
            int o = arrBody.indexOf('{');
            while (o >= 0) {
                int d = 0, j = o; boolean ins = false, e2 = false;
                for (; j < arrBody.length(); j++) {
                    char c = arrBody.charAt(j);
                    if (ins) { if (e2) e2 = false; else if (c == '\\') e2 = true; else if (c == '"') ins = false; continue; }
                    if (c == '"') ins = true;
                    else if (c == '{') d++;
                    else if (c == '}') { d--; if (d == 0) break; }
                }
                KeyMistake km = parseMistake(arrBody.substring(o, j + 1));
                if (km != null) out.add(km);
                o = arrBody.indexOf('{', j + 1);
            }
            return out;
        }
        String findStringField(String key) {
            int idx = src.indexOf("\"" + key + "\"");
            if (idx < 0) return null;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return null;
            int i = colon + 1;
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            if (i >= src.length() || src.charAt(i) != '"') return null;
            int start = i + 1; int j = start; boolean inStr = true, esc = false;
            for (; j < src.length(); j++) {
                char c = src.charAt(j);
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') { inStr = false; break; }
            }
            return unescape(src.substring(start, j));
        }
        /** 对齐 TS：moveNumber 等字段可能是裸数字（非引号字符串） */
        String findNumberField(String key) {
            int idx = src.indexOf("\"" + key + "\"");
            if (idx < 0) return null;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return null;
            int i = colon + 1;
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            if (i >= src.length()) return null;
            if (src.charAt(i) == '"') {
                // 引号字符串形式
                int start = i + 1; int j = start; boolean esc = false;
                for (; j < src.length(); j++) {
                    char c = src.charAt(j);
                    if (esc) { esc = false; continue; }
                    if (c == '\\') { esc = true; continue; }
                    if (c == '"') break;
                }
                return unescape(src.substring(start, j));
            }
            // 裸数字：读数字/负号/小数点
            StringBuilder sb = new StringBuilder();
            int j = i;
            while (j < src.length()) {
                char c = src.charAt(j);
                if (Character.isDigit(c) || c == '-' || c == '.') { sb.append(c); j++; }
                else break;
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
    }

    static KeyMistake parseMistake(String objSrc) {
        SimpleJson o = new SimpleJson(objSrc);
        KeyMistake km = new KeyMistake();
        String mn = o.findNumberField("moveNumber");
        if (mn != null) try { km.moveNumber = Integer.parseInt(mn.trim()); } catch (Exception ignore) {}
        String colorRaw = o.findStringField("color");
        km.color = ("B".equals(colorRaw) || "W".equals(colorRaw)) ? colorRaw : null;
        km.played = o.findStringField("played");
        km.recommended = o.findStringField("recommended");
        km.errorType = o.findStringField("errorType");
        km.severity = o.findStringField("severity");
        if (!("blunder".equals(km.severity) || "mistake".equals(km.severity) || "inaccuracy".equals(km.severity))) km.severity = "mistake";
        km.evidence = o.findStringField("evidence");
        km.explanation = o.findStringField("explanation");
        return km;
    }

    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
