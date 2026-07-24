package org.example.bot.tools;

import com.google.gson.JsonObject;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具中心 — 统一管理所有 Function Calling 工具的注册、条件评估和查询。
 *
 * <h3>使用方式</h3>
 * <pre>
 *   ToolCenter center = new ToolCenter();
 *   center.register(new ToolDefinition("my_tool", "desc", params, executor));
 *   center.register(someContributor);  // 批量注册
 *   center.buildTools(toolsList, executorsMap, userId);  // 每次请求时调用
 * </pre>
 *
 * <h3>与 BotApp 集成</h3>
 * 在 processTextMessage() 中，原有的 buildTools() 调用之后追加：
 * <pre>
 *   toolCenter.buildTools(tools, executors, userId);
 * </pre>
 */
public class ToolCenter {

    private final List<ToolDefinition> registry = new ArrayList<>();

    /** ThreadLocal — 在当前请求线程中传递 userId，供缓存类 executor 使用 */
    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    /** 获取当前请求的 userId（供 executor 内部使用，如缓存查询） */
    public static String currentUserId() {
        return currentUserId.get();
    }

    // ==================== 注册 ====================

    /** 注册单个工具定义，返回 this 支持链式调用 */
    public ToolCenter register(ToolDefinition def) {
        registry.add(def);
        return this;
    }

    /** 批量注册（从 ToolContributor） */
    public ToolCenter register(ToolContributor contributor) {
        contributor.contributeTo(this);
        return this;
    }

    /** 批量注册多个 ToolDefinition */
    public ToolCenter registerAll(Collection<ToolDefinition> defs) {
        registry.addAll(defs);
        return this;
    }

    // ==================== 构建 ====================

    /**
     * 将中心内所有满足条件的工具追加到给定的工具列表和执行器映射中。
     *
     * @param tools     工具定义列表（由现有 buildTools() 已填充，本方法追加）
     * @param executors 工具名 → 执行器的映射（同上，追加）
     * @param userId    当前用户 ID，用于评估条件
     */
    public void buildTools(List<FunctionDefinition> tools,
                           Map<String, Function<JsonObject, String>> executors,
                           String userId) {
        currentUserId.set(userId);
        try {
            for (ToolDefinition def : registry) {
                if (!def.isAvailable(userId)) {
                    continue;
                }
                // 去重：已存在的工具不重复注册（保留 buildTools 中的原有定义）
                if (executors.containsKey(def.name())) {
                    continue;
                }
                tools.add(toFunctionDefinition(def));
                executors.put(def.name(), def.executor());
            }
        } finally {
            currentUserId.remove();
        }
    }

    // ==================== 查询 ====================

    /** 列出所有已注册的工具名称（不含条件过滤） */
    public List<String> listAllNames() {
        return registry.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toList());
    }

    /** 列出对指定用户当前可用的工具名称 */
    public List<String> listAvailableNames(String userId) {
        return registry.stream()
                .filter(d -> d.isAvailable(userId))
                .map(ToolDefinition::name)
                .collect(Collectors.toList());
    }

    /** 按名称查询工具定义 */
    public Optional<ToolDefinition> getByName(String name) {
        return registry.stream()
                .filter(d -> d.name().equals(name))
                .findFirst();
    }

    /** 已注册工具总数 */
    public int size() {
        return registry.size();
    }

    /** 对指定用户可用的工具数 */
    public int availableCount(String userId) {
        return (int) registry.stream()
                .filter(d -> d.isAvailable(userId)).count();
    }

    /** 工具中心概览（用于启动日志） */
    public String summary() {
        StringBuilder sb = new StringBuilder("[ToolCenter] ")
                .append(registry.size()).append(" 个工具已注册:\n");
        for (ToolDefinition d : registry) {
            sb.append("  • ").append(d.name());
            if (d.condition != null) sb.append(" [条件]");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部工具方法 ====================

    /** 将 ToolDefinition 转换为 OpenAI SDK 的 FunctionDefinition */
    @SuppressWarnings("unchecked")
    private static FunctionDefinition toFunctionDefinition(ToolDefinition def) {
        return FunctionDefinition.builder()
                .name(def.name())
                .description(def.description())
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties",
                                JsonValue.from((Object) def.parameters()))
                        .build())
                .build();
    }
}
