package featurecat.lizzie.teacher.knowledge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GoAgent data/knowledge 下的 markdown 棋理知识文档（proverbs/shapes/strategy/life-death/joseki/ko/tesuji/endgame，39 篇）。
 * GoAgent 运行时未加载这些文档（属静态知识资产），这里提供按需加载 + 关键词搜索，
 * 使讲解可引用棋理格言/死活/手筋/官子等教学文本。
 */
public final class MarkdownKnowledgeLoader {

    private MarkdownKnowledgeLoader() {}

    public static final String[] CATEGORIES = {"proverbs", "shapes", "strategy", "life-death", "joseki", "ko", "tesuji", "endgame"};

    private static final Map<String, Map<String, String>> cache = new HashMap<>(); // category -> (title -> content)

    public static Map<String, String> loadCategory(String category) {
        if (category == null) return Collections.emptyMap();
        String key = category.toLowerCase();
        Map<String, String> cached = cache.get(key);
        if (cached != null) return cached;
        Map<String, String> out = new LinkedHashMap<>();
        // 资源文件在 markdown/<category>/ 下；文件名带 .md
        List<String> files = knownFiles(key);
        for (String file : files) {
            try (InputStream in = MarkdownKnowledgeLoader.class.getResourceAsStream("/knowledge/markdown/" + key + "/" + file)) {
                if (in == null) continue;
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String title = file.replace(".md", "");
                out.put(title, text);
            } catch (Exception e) { /* skip */ }
        }
        cache.put(key, out);
        return out;
    }

    static List<String> knownFiles(String category) {
        switch (category) {
            case "proverbs": return Arrays.asList("ai-insights.md", "direction-thickness.md", "endgame.md", "middle-game.md", "opening.md");
            case "shapes": return Arrays.asList("connections.md", "eye-shapes.md", "good-bad-shapes.md");
            case "strategy": return Arrays.asList("aji.md", "attack-defense.md", "big-urgent.md", "counting.md", "fuseki-systems.md", "handicap.md", "heavy-light.md", "influence-territory.md", "invasion-reduction.md", "moyo.md", "reading.md", "sabaki.md", "semeai.md", "sente-gote.md", "vital-expendable.md");
            case "life-death": return Arrays.asList("basics.md", "corner-patterns.md", "seki.md", "side-patterns.md");
            case "joseki": return Arrays.asList("33-invasion.md", "34-point.md", "high-approach.md", "star-point.md");
            case "ko": return Arrays.asList("basics.md", "ko-fights.md");
            case "tesuji": return Arrays.asList("common-tesuji.md", "cutting.md", "peep-probe.md", "sacrifice.md");
            case "endgame": return Arrays.asList("counting-endgame.md", "endgame-tesuji.md");
            default: return Collections.emptyList();
        }
    }

    /** 全量加载（所有类别） */
    public static Map<String, String> loadAll() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String cat : CATEGORIES) {
            for (Map.Entry<String, String> e : loadCategory(cat).entrySet()) all.put(cat + "/" + e.getKey(), e.getValue());
        }
        return all;
    }

    /** 按关键词搜索所有文档，返回 [category/title, 匹配段落] 列表（最多 limit 条） */
    public static List<String[]> search(String keyword, int limit) {
        List<String[]> results = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return results;
        String kw = keyword.trim().toLowerCase();
        for (String cat : CATEGORIES) {
            for (Map.Entry<String, String> e : loadCategory(cat).entrySet()) {
                String content = e.getValue();
                String lower = content.toLowerCase();
                int idx = lower.indexOf(kw);
                if (idx < 0) continue;
                // 取匹配位置前后约 200 字符作为摘要
                int start = Math.max(0, idx - 100);
                int end = Math.min(content.length(), idx + kw.length() + 200);
                String snippet = content.substring(start, end).replaceAll("\\s+", " ").trim();
                results.add(new String[]{cat + "/" + e.getKey(), snippet});
                if (results.size() >= limit) return results;
            }
        }
        return results;
    }

    /** 加载单篇文档全文 */
    public static String loadDocument(String category, String title) {
        return loadCategory(category).get(title);
    }

    /** 格式化为 prompt 段落 */
    public static String formatForPrompt(List<String[]> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【棋理知识参考】\n");
        for (String[] r : results) {
            sb.append("- ").append(r[0]).append("：").append(r[1]).append("\n");
        }
        return sb.toString();
    }
}
