package weather;

/**
 * 工具执行结果
 */
public class ToolResult {
    public final boolean success;
    public final String content;

    public ToolResult(boolean success, String content) {
        this.success = success;
        this.content = content;
    }
}