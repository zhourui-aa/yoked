package weather;

import com.google.gson.JsonObject;

/**
 * 可被 AI Function Calling 调用的工具接口
 */
public interface Tool {
    /** 工具唯一标识（英文，用于 function calling） */
    String name();

    /** 工具功能描述（AI 根据此描述判断何时调用） */
    String description();

    /** 参数 JSON Schema（OpenAI 兼容格式） */
    JsonObject parametersSchema();

    /** 执行工具逻辑 */
    ToolResult execute(JsonObject args);
}