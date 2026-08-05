package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.List;

/** 对齐 GoAgent teacher/toolLogBuilder.ts：工具调用日志构建（用于讲解过程透明化） */
public final class ToolLogBuilder {

    private ToolLogBuilder() {}

    public enum ToolLogStatus { running, done, error, skipped }
    public static class TeacherToolLog {
        public String id, label, status, detail, startedAt, finishedAt, errorCode;
    }

    public static TeacherToolLog start(String id, String label, String detail) {
        TeacherToolLog log = new TeacherToolLog();
        log.id = id; log.label = label; log.status = "running"; log.detail = detail != null ? detail : "";
        log.startedAt = now();
        return log;
    }
    public static TeacherToolLog finish(TeacherToolLog log, String detail) {
        log.status = "done"; log.detail = detail != null ? detail : log.detail; log.finishedAt = now();
        return log;
    }
    public static TeacherToolLog fail(TeacherToolLog log, Object error, String errorCode) {
        log.status = "error"; log.detail = error instanceof Exception ? ((Exception) error).getMessage() : String.valueOf(error);
        log.errorCode = errorCode; log.finishedAt = now();
        return log;
    }
    public static TeacherToolLog skipped(String id, String label, String detail) {
        TeacherToolLog log = new TeacherToolLog();
        log.id = id; log.label = label; log.status = "skipped"; log.detail = detail;
        log.startedAt = now(); log.finishedAt = now();
        return log;
    }
    public static String compactForPrompt(List<TeacherToolLog> logs) {
        StringBuilder sb = new StringBuilder();
        for (TeacherToolLog log : logs) sb.append(log.label).append(": ").append(log.status).append(" - ").append(log.detail).append("\n");
        return sb.toString();
    }
    static String now() { return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date()); }
}
