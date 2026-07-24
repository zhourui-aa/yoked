package org.example.bot.tools;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 单个 Function Calling 工具的定义。
 *
 * <p>封装了原来在 buildTools() 中内联编写的两部分：
 * <ol>
 *   <li>{@code tools.add(functionDef(name, description, params))} — 工具描述</li>
 *   <li>{@code executors.put(name, args -> { ... })} — 执行器</li>
 * </ol>
 * 外加一个可选的条件，控制该工具是否对某个用户可见。
 */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Function<JsonObject, String> executor;
    final ToolCondition condition; // package-private for ToolCenter

    /** 无条件注册 */
    public ToolDefinition(String name, String description,
                          Map<String, Object> parameters,
                          Function<JsonObject, String> executor) {
        this(name, description, parameters, executor, null);
    }

    /** 条件注册 — condition 为 null 时等同于无条件 */
    public ToolDefinition(String name, String description,
                          Map<String, Object> parameters,
                          Function<JsonObject, String> executor,
                          ToolCondition condition) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.parameters = parameters != null
                ? Collections.unmodifiableMap(parameters) : Collections.emptyMap();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.condition = condition;
    }

    public String name()              { return name; }
    public String description()       { return description; }
    public Map<String, Object> parameters() { return parameters; }
    public Function<JsonObject, String> executor() { return executor; }

    /** 判断此工具对指定用户是否可用 */
    public boolean isAvailable(String userId) {
        return condition == null || condition.test(userId);
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "'"
                + (condition != null ? ", conditional" : "") + "}";
    }
}
