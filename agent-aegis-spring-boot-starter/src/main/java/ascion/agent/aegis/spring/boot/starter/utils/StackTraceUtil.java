package ascion.agent.aegis.spring.boot.starter.utils;

public class StackTraceUtil {

    /**
     * 格式化堆栈信息，避免 Arrays.toString(e.getStackTrace()) 格式过于凌乱
     */
    public static String getStackTraceAsString(Throwable throwable) {
        StringBuilder sb = new StringBuilder(throwable.toString()).append("\n");
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
