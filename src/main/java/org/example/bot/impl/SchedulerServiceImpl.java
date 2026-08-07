package org.example.bot.impl;

import org.example.bot.service.DatabaseService;
import org.example.bot.service.SchedulerService;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时任务服务实现 — ScheduledExecutorService + SQLite 持久化。
 */
public class SchedulerServiceImpl implements SchedulerService {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final DatabaseService db;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, TaskInfo> fireHandler;

    public SchedulerServiceImpl(DatabaseService db) {
        this.db = db;
        reloadFromDb();
        System.out.println("[定时] 定时任务服务已就绪（" + futures.size() + " 个待执行任务）");
    }

    @Override
    public void setFireHandler(BiConsumer<String, TaskInfo> handler) {
        this.fireHandler = handler;
    }

    // ==================== schedule ====================

    @Override
    public String schedule(String userId, String botName, String type,
                           String cronOrDelay, String message) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        long delayMs;
        if ("once".equals(type)) {
            delayMs = parseDelay(cronOrDelay);
            if (delayMs <= 0) delayMs = 60_000; // 默认 1 分钟
        } else {
            delayMs = nextCronDelay(cronOrDelay);
            if (delayMs <= 0) delayMs = 3600_000; // 默认 1 小时
        }

        long nextFire = now + delayMs / 1000;

        // 存 DB
        db.saveTask(new DatabaseService.TaskRecord(
            taskId, userId, botName, type, cronOrDelay, message, nextFire, now));

        // 加入调度
        scheduleTask(taskId, userId, botName, type, cronOrDelay, message, delayMs);

        return taskId;
    }

    // ==================== cancel ====================

    @Override
    public String cancel(String taskId) {
        ScheduledFuture<?> f = futures.remove(taskId);
        if (f != null) f.cancel(false);
        db.deleteTask(taskId);
        return "✅ 已取消任务 " + taskId;
    }

    // ==================== list ====================

    @Override
    public List<TaskInfo> listTasks(String userId) {
        return db.loadAllTasks().stream()
            .filter(t -> t.userId().equals(userId) && t.nextFire() > System.currentTimeMillis() / 1000)
            .map(t -> new TaskInfo(t.id(), t.userId(), t.botName(), t.type(),
                t.cronDelay(), t.message(), t.nextFire()))
            .toList();
    }

    // ==================== internal ====================

    private void scheduleTask(String taskId, String userId, String botName,
                              String type, String cronDelay, String message, long delayMs) {
        Runnable action = () -> {
            TaskInfo info = new TaskInfo(taskId, userId, botName, type, cronDelay, message, 0);
            if (fireHandler != null) fireHandler.accept(userId, info);

            if ("once".equals(type)) {
                futures.remove(taskId);
                db.deleteTask(taskId);
            } else {
                // 重新调度下次
                long nextMs = nextCronDelay(cronDelay);
                long nextFire = System.currentTimeMillis() / 1000 + nextMs / 1000;
                db.saveTask(new DatabaseService.TaskRecord(
                    taskId, userId, botName, type, cronDelay, message, nextFire,
                    System.currentTimeMillis() / 1000));
                scheduleTask(taskId, userId, botName, type, cronDelay, message, nextMs);
            }
        };

        ScheduledFuture<?> future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS);
        futures.put(taskId, future);
    }

    private void reloadFromDb() {
        long now = System.currentTimeMillis() / 1000;
        for (var t : db.loadAllTasks()) {
            if (t.nextFire() <= now && "once".equals(t.type())) {
                db.deleteTask(t.id());
                continue;
            }
            long delayMs = Math.max(0, (t.nextFire() - now) * 1000);
            scheduleTask(t.id(), t.userId(), t.botName(), t.type(), t.cronDelay(), t.message(), delayMs);
        }
    }

    // ==================== 时间解析 ====================

    private static final Pattern DELAY_PAT = Pattern.compile("(\\d+)\\s*(秒|分钟?|小时|天)");

    /** "5分钟" / "30秒" / "1小时" / "明天9点" → 毫秒 */
    static long parseDelay(String text) {
        Matcher m = DELAY_PAT.matcher(text);
        if (m.find()) {
            long num = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "秒" -> num * 1000;
                case "分", "分钟" -> num * 60_000;
                case "小时" -> num * 3600_000;
                case "天" -> num * 86400_000;
                default -> 60_000;
            };
        }
        // 纯数字 = 分钟
        try { return Long.parseLong(text.trim()) * 60_000; }
        catch (NumberFormatException e) { return 60_000; }
    }

    /** Cron 表达式 → 到下次触发的毫秒数 */
    static long nextCronDelay(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 5) return 3600_000;

        int targetMin = parseField(parts[0], 0, 59);
        int targetHour = parseField(parts[1], 0, 23);
        int targetDay = parseField(parts[2], 1, 31);
        int targetMonth = parseField(parts[3], 1, 12);
        int targetDow = parseField(parts[4], 0, 6);

        Calendar now = Calendar.getInstance();
        // 从下一分钟开始逐个候选，找到第一个满足全部字段的时间（处理月/周跨周期推进）
        Calendar candidate = (Calendar) now.clone();
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);
        candidate.add(Calendar.MINUTE, 1);

        for (int attempt = 0; attempt < 366 * 24 * 60; attempt++) { // 上限一年，防死循环
            boolean match = true;
            if (targetMin >= 0 && candidate.get(Calendar.MINUTE) != targetMin) match = false;
            if (targetHour >= 0 && candidate.get(Calendar.HOUR_OF_DAY) != targetHour) match = false;
            if (targetDay >= 0 && candidate.get(Calendar.DAY_OF_MONTH) != targetDay) match = false;
            if (targetMonth >= 0 && candidate.get(Calendar.MONTH) + 1 != targetMonth) match = false;
            if (targetDow >= 0) {
                // Calendar.SUNDAY=1 ... SATURDAY=7，cron 0=周日
                int dow = candidate.get(Calendar.DAY_OF_WEEK) - 1; // 0=周日
                if (dow != targetDow) match = false;
            }
            if (match) {
                return candidate.getTimeInMillis() - now.getTimeInMillis();
            }
            candidate.add(Calendar.MINUTE, 1);
        }
        return 3600_000; // 兜底：一年内找不到则 1 小时后
    }

    private static int parseField(String f, int min, int max) {
        if ("*".equals(f)) return -1;
        try { return Integer.parseInt(f); }
        catch (NumberFormatException e) { return -1; }
    }
}
