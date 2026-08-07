package featurecat.lizzie.teacher.analysis;

/**
 * 对齐 GoAgent 的 teacherPersona.ts（157 行）全量：
 * buildTeacherPersonaInstruction（level/rank/exactAge/ageRange/style/terminologyDensity/explanationPace/variationDetail）
 * + TEACHER_STYLE_LABELS / STUDENT_AGE_LABELS + normalize* 系列函数。
 */
public final class TeacherPersona {

    private TeacherPersona() {}

    public enum Level { BEGINNER, INTERMEDIATE, ADVANCED, DAN }
    public enum Style { BALANCED, RIGOROUS, GENTLE, STRICT, HUMOROUS }
    public enum Rank { SUB1D, D1, D2, D3, D4, D5, D6, D7, D8, D9 }
    public enum AgeRange { UNKNOWN, CHILD, TEEN, ADULT, SENIOR }
    public enum TerminologyDensity { LOW, MEDIUM, HIGH }
    public enum ExplanationPace { BRIEF, STANDARD, DETAILED }
    public enum VariationDetail { FEW, MODERATE, MANY }

    public static final java.util.Map<Style, String> TEACHER_STYLE_LABELS = java.util.Map.of(
        Style.BALANCED, "平衡自然", Style.RIGOROUS, "严谨细致", Style.GENTLE, "温柔和蔼",
        Style.STRICT, "严格专业", Style.HUMOROUS, "风趣幽默");
    public static final java.util.Map<AgeRange, String> STUDENT_AGE_LABELS = java.util.Map.of(
        AgeRange.UNKNOWN, "未指定年龄", AgeRange.CHILD, "儿童", AgeRange.TEEN, "青少年",
        AgeRange.ADULT, "成年人", AgeRange.SENIOR, "年长学习者");

    public static class TeacherPersonaInput {
        public Level level = Level.INTERMEDIATE;
        public Rank rank;
        public Integer exactAge;
        public AgeRange ageRange;
        public Style style = Style.BALANCED;
        public TerminologyDensity terminologyDensity = TerminologyDensity.MEDIUM;
        public ExplanationPace explanationPace = ExplanationPace.STANDARD;
        public VariationDetail variationDetail = VariationDetail.MODERATE;
    }

    static String levelInstruction(Level level) {
        if (level == Level.BEGINNER) return "当前用户是入门水平。少用术语，优先讲“这手下一次怎么想”、气、断点、连接、先后手和一两个可执行提醒。PV 不要展开太长。";
        if (level == Level.INTERMEDIATE) return "当前用户是级位/中级水平。可以讲 1-2 个关键变化，重点解释棋形目的、目差代价和常见误区。";
        if (level == Level.ADVANCED) return "当前用户是高级水平。可以比较候选点、方向、转换、ownership 和 PV，但仍要先讲判断顺序。";
        return "当前用户是段位水平。可以讲更细的目差、候选分歧、PV 支撑、ownership 摆动和局面策略，但必须保持证据可追溯。";
    }
    static String rankInstruction(Rank rank) {
        if (rank == null) return "";
        switch (rank) {
            case SUB1D: return "当前用户段位：1k以下（级位）。优先讲可执行的下一手判断、方向和基本形状，避免堆变化。";
            case D1: return "当前用户段位：1d。可以比较候选点、方向和厚薄转换。";
            case D2: return "当前用户段位：2d。可以讲关键变化、方向选择和攻防先后。";
            case D3: return "当前用户段位：3d。可以讲更细的目差、PV 分歧和中盘攻防节奏。";
            case D4: return "当前用户段位：4d。可以讲转换价值、局部收益与全局厚薄的权衡。";
            case D5: return "当前用户段位：5d。可以讲高阶次序、交换价值、全局转换和局部读秒级判断。";
            case D6: return "当前用户段位：6d。可以更直接比较复杂变化、劫材价值和战略转换。";
            case D7: return "当前用户段位：7d。可以讲深层次序、细微目差和胜率风险控制。";
            case D8: return "当前用户段位：8d。可以讲接近职业训练的候选点取舍和高精度转换。";
            case D9: return "当前用户段位：9d。可以使用高密度术语、复杂 PV 对照和职业级证据链复盘。";
        }
        return "";
    }
    static String ageInstruction(AgeRange ageRange) {
        if (ageRange == AgeRange.CHILD) return "用户年龄偏小。句子要短，避免讽刺和过度批评，多用具体动作和小练习；不要用恐吓式语言。";
        if (ageRange == AgeRange.TEEN) return "用户是青少年。可以直接指出问题，但要保留鼓励和下一步训练目标。";
        if (ageRange == AgeRange.ADULT) return "用户是成年人。讲解可以更直接，重点给出复盘方法、判断顺序和训练安排。";
        if (ageRange == AgeRange.SENIOR) return "用户是年长学习者。节奏放慢，少堆术语，多用清晰结构和复盘步骤。";
        return "用户年龄未指定。按普通成人讲解，避免过度假设。";
    }
    static String exactAgeInstruction(Integer exactAge) {
        if (exactAge == null || exactAge < 1) return "";
        return "用户年龄：" + exactAge + " 岁。年龄只用于调整表达节奏，不能改变事实判断。";
    }
    static String styleInstruction(Style style) {
        if (style == Style.RIGOROUS) return "你的风格：严谨细致。结构清晰，证据完整，坐标、目差、置信度和变化分支要交代清楚。";
        if (style == Style.GENTLE) return "你的风格：温柔和蔼。少用“恶手/崩了”等刺激词，多说“这里可以换个思路”，但不能淡化关键错误。";
        if (style == Style.STRICT) return "你的风格：严格专业。可以直接指出问题和训练要求，但低置信度证据下仍禁止“唯一、必败、必杀、绝对”等强断言。";
        if (style == Style.HUMOROUS) return "你的风格：风趣幽默。可以轻微比喻，但幽默只能服务理解，不能编故事、编棋理、编坐标、编胜率或牺牲准确性。";
        return "你的风格：平衡自然。先讲判断，再讲原因，最后给一个可执行提醒。";
    }
    static String densityInstruction(TerminologyDensity density) {
        if (density == TerminologyDensity.LOW) return "术语密度：少。每次最多引入少量术语，先用自然语言解释。";
        if (density == TerminologyDensity.HIGH) return "术语密度：多。可以使用专业术语，但每个关键术语仍需和证据绑定。";
        return "术语密度：中。术语和自然语言保持平衡。";
    }
    static String paceInstruction(ExplanationPace pace) {
        if (pace == ExplanationPace.BRIEF) return "讲解节奏：简洁。优先给结论和一条行动建议。";
        if (pace == ExplanationPace.DETAILED) return "讲解节奏：细讲。可以补充判断过程、应手和后续训练建议。";
        return "讲解节奏：标准。先讲走势，再讲关键证据，最后给下一步。";
    }
    static String variationInstruction(VariationDetail detail) {
        if (detail == VariationDetail.FEW) return "参考变化：少讲。只讲最关键的 1 个变化或直接建议。";
        if (detail == VariationDetail.MANY) return "参考变化：详细。证据充足时可讲 2-3 个重要分支。";
        return "参考变化：适中。只展开最能说明问题的变化。";
    }

    /** 对齐 buildTeacherPersonaInstruction */
    public static String buildTeacherPersonaInstruction(TeacherPersonaInput input) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add("【风格设置】");
        parts.add(levelInstruction(input.level));
        String r = rankInstruction(input.rank); if (!r.isEmpty()) parts.add(r);
        String e = exactAgeInstruction(input.exactAge); if (!e.isEmpty()) parts.add(e);
        parts.add(ageInstruction(input.ageRange));
        parts.add(styleInstruction(input.style));
        parts.add(densityInstruction(input.terminologyDensity));
        parts.add(paceInstruction(input.explanationPace));
        parts.add(variationInstruction(input.variationDetail));
        parts.add("风格和年龄只影响表达方式、讲解节奏、术语密度和训练建议；不能改变 KataGo、TeachingEvidence、棋形识别、PV、坐标、胜率、目差、定式名或死活结论。");
        parts.add("如果风格要求和证据约束冲突，永远以证据约束为准。");
        return String.join("\n", parts);
    }

    // ---- normalize* 系列 ----
    public static Level normalizeCoachLevel(String value) {
        if (value == null) return Level.INTERMEDIATE;
        return switch (value) {
            case "beginner" -> Level.BEGINNER;
            case "advanced" -> Level.ADVANCED;
            case "dan" -> Level.DAN;
            default -> Level.INTERMEDIATE;
        };
    }
    public static AgeRange normalizeStudentAgeRange(String value) {
        if (value == null) return AgeRange.UNKNOWN;
        return switch (value) {
            case "child" -> AgeRange.CHILD;
            case "teen" -> AgeRange.TEEN;
            case "adult" -> AgeRange.ADULT;
            case "senior" -> AgeRange.SENIOR;
            default -> AgeRange.UNKNOWN;
        };
    }
    public static Style normalizeTeacherStyle(String value) {
        if (value == null) return Style.BALANCED;
        return switch (value) {
            case "rigorous" -> Style.RIGOROUS;
            case "gentle" -> Style.GENTLE;
            case "strict" -> Style.STRICT;
            case "humorous" -> Style.HUMOROUS;
            default -> Style.BALANCED;
        };
    }
    public static Rank normalizeStudentRank(String value) {
        if (value == null) return Rank.SUB1D;
        if (value.equals("10k") || value.equals("1k")) return Rank.SUB1D;
        return switch (value) {
            case "1d" -> Rank.D1; case "2d" -> Rank.D2; case "3d" -> Rank.D3; case "4d" -> Rank.D4;
            case "5d" -> Rank.D5; case "6d" -> Rank.D6; case "7d" -> Rank.D7; case "8d" -> Rank.D8; case "9d" -> Rank.D9;
            default -> Rank.SUB1D;
        };
    }
    public static int normalizeExactStudentAge(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) return 0;
        return Math.max(0, Math.min(120, (int) Math.round(((Number) value).doubleValue())));
    }
    public static TerminologyDensity normalizeTerminologyDensity(String value) {
        if (value == null) return TerminologyDensity.MEDIUM;
        return switch (value) {
            case "low" -> TerminologyDensity.LOW;
            case "high" -> TerminologyDensity.HIGH;
            default -> TerminologyDensity.MEDIUM;
        };
    }
    public static ExplanationPace normalizeExplanationPace(String value) {
        if (value == null) return ExplanationPace.STANDARD;
        return switch (value) {
            case "brief" -> ExplanationPace.BRIEF;
            case "detailed" -> ExplanationPace.DETAILED;
            default -> ExplanationPace.STANDARD;
        };
    }
    /** 兼容旧调用：组合 persona 指令（level/age/style） */
    public static String buildPersona(Level level, int age, Style style) {
        TeacherPersonaInput in = new TeacherPersonaInput();
        in.level = level; in.exactAge = age > 0 ? age : null; in.style = style;
        if (age > 0) {
            if (age <= 12) in.ageRange = AgeRange.CHILD;
            else if (age <= 17) in.ageRange = AgeRange.TEEN;
            else if (age >= 60) in.ageRange = AgeRange.SENIOR;
            else in.ageRange = AgeRange.ADULT;
        }
        return buildTeacherPersonaInstruction(in);
    }

    public static VariationDetail normalizeVariationDetail(String value) {
        if (value == null) return VariationDetail.MODERATE;
        return switch (value) {
            case "few" -> VariationDetail.FEW;
            case "many" -> VariationDetail.MANY;
            default -> VariationDetail.MODERATE;
        };
    }
}
