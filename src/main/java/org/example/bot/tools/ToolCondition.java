package org.example.bot.tools;

/**
 * 工具注册条件 — 在构建工具列表时评估，决定某个工具是否对当前用户可用。
 *
 * <p>典型用途：
 * <ul>
 *   <li>服务是否已初始化（captured 引用非空）— 在注册时由调用方判断</li>
 *   <li>用户是否有缓存数据（图片缓存、文档缓存等）— per-request 评估</li>
 *   <li>服务运行时可用性 — per-request 评估</li>
 * </ul>
 */
@FunctionalInterface
public interface ToolCondition {
    /**
     * @param userId 当前用户 ID，用于缓存等 per-user 判断
     * @return true 表示该工具应该被注册到当前请求的工具列表中
     */
    boolean test(String userId);
}
