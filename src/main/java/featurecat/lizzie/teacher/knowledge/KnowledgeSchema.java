package featurecat.lizzie.teacher.knowledge;

/** 对齐 GoAgent knowledge/schema.ts：知识卡类型与查询模型（Java 版） */
public final class KnowledgeSchema {

    private KnowledgeSchema() {}

    public enum KnowledgeCardKind {
        concept, error_type, position_pattern, training, review_method,
        joseki, life_death, tesuji_pattern, shape_pattern
    }

    public enum GamePhase { opening, middlegame, endgame }

    public static class KnowledgeCard {
        public String id;
        public String title;
        public KnowledgeCardKind kind;
        public GamePhase[] phase;
        public String[] errorTypes;
        public String[] tags;
        public String[] katagoSignals;
        public String[] boardSignals;
        public String summary;
        public String coachShort;
        public String coachLong;
        public String drill;
        public String[] related;
    }

    public static class KnowledgeSearchQuery {
        public String text;
        public GamePhase phase;
        public String[] errorTypes;
        public String[] tags;
        public int limit = 5;
    }

    public static class KnowledgeSearchResult {
        public KnowledgeCard card;
        public double score;
        public String[] reasons;
    }
}
