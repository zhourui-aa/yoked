package org.example.bot.service;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 定时任务服务接口 — 一次性提醒和定期重复任务。
 */
public interface SchedulerService {

    /**
     * 创建定时任务。
     * @param userId      用户 ID
     * @param botName     bot 名称
     * @param type        "once" 一次性 / "recurring" 定期
     * @param cronOrDelay 一次性=延迟描述(如"5分钟"/"明天9点")，定期=cron(如"0 8 * * *")
     * @param message     提醒内容
     * @return 任务 ID
     */
    String schedule(String userId, String botName, String type, String cronOrDelay, String message);

    /** 取消任务 */
    String cancel(String taskId);

    /** 列出用户的所有任务 */
    List<TaskInfo> listTasks(String userId);

    /** 设置触发回调（BotApp 注入） */
    void setFireHandler(BiConsumer<String, TaskInfo> handler);

    /** 任务信息 */
    record TaskInfo(String id, String userId, String botName, String type,
                    String cronDelay, String message, long nextFire) {}
}
