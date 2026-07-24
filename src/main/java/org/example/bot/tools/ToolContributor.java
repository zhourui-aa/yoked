package org.example.bot.tools;

/**
 * 工具贡献者接口。
 * 任何服务实现此接口后，即可向 ToolCenter 批量注册自己的工具。
 *
 * <pre>
 * // 在 impl 类中：
 * public class FootballServiceImpl implements FootballService, ToolContributor {
 *     &#64;Override
 *     public void contributeTo(ToolCenter center) {
 *         center.register(new ToolDefinition("get_premier_league_standings", ...));
 *         center.register(new ToolDefinition("get_premier_league_matches", ...));
 *     }
 * }
 *
 * // 在 BotApp.main() 中：
 * if (football != null) {
 *     football.contributeTo(toolCenter);
 * }
 * </pre>
 */
@FunctionalInterface
public interface ToolContributor {
    /**
     * 向工具中心贡献本服务提供的所有工具。
     * 调用时机：服务初始化完成后，在 BotApp.main() 中调用。
     */
    void contributeTo(ToolCenter center);
}
