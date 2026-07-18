package org.example.bot;

import org.example.bot.ilink.ILinkBot;
import org.example.bot.model.BotMessage;
import org.example.bot.model.DrawIntent;
import org.example.bot.model.WeatherIntent;
import org.example.bot.service.AiService;
import org.example.bot.service.ImageGenService;
import org.example.bot.service.VisionService;
import org.example.bot.service.WeatherBotService;
import org.example.bot.impl.DeepSeekAiServiceImpl;
import org.example.bot.impl.DoubaoVisionServiceImpl;
import org.example.bot.impl.SeedreamImageServiceImpl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 微信 AI 聊天机器人 — 主程序入口。
 *
 * <h3>消息路由策略</h3>
 * <ol>
 *   <li>图片消息 → 视觉识别（Vision API）</li>
 *   <li>文字包含"画/生成/图"等关键词 → AI 判断是否为生图意图 → Seedream 生图</li>
 *   <li>文字包含"天气/气温/下雨"等关键词 → AI 判断是否为天气查询 → 和风天气</li>
 *   <li>以上都不匹配 → DeepSeek 自由对话</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>{@code mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp"}</pre>
 */
public class BotApp {

    private static final String SYSTEM_PROMPT = "你是一个友好的微信助手，请用简洁自然的中文回复。";
    private static final long POLL_INTERVAL_MS = 2000;

    /** 生图专用线程池 — 避免阻塞主消息循环 */
    private static final ExecutorService IMAGE_EXECUTOR =
        Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "image-gen");
            t.setDaemon(true);
            return t;
        });

    // ---- 关键词预筛（降低 AI 调用频率）----
    // 生图相关关键词 — 命中后才会调用 AI 做精确意图提取
    private static final String[] DRAW_KEYWORDS = {
        "画", "生成", "图", "来一", "出张", "出个", "做张", "做一", "图片",
        "画一", "整一", "搞一", "弄一", "来张", "做个", "生成个", "画个"
    };
    // 天气相关关键词 — 命中后才会调用 AI 做精确意图提取
    private static final String[] WEATHER_KEYWORDS = {
        "天气", "气温", "多少度", "下雨", "冷不冷", "热不热", "刮风",
        "雾霾", "晴天", "阴天", "多云", "带伞", "穿什么", "温度", "湿度",
        "雪", "台风", "冰雹", "霜冻", "天气预报", "凉快", "闷热"
    };

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 AI 聊天机器人 启动中...");
        System.out.println("========================================");

        // 第 1 步：创建并登录微信机器人
        ILinkBot bot = ILinkBot.create();
        bot.login();

        // 第 2 步：创建服务
        AiService ai = new DeepSeekAiServiceImpl(SYSTEM_PROMPT);
        WeatherBotService weather = WeatherBotService.create();

        ImageGenService imageGen = null;
        try {
            imageGen = new SeedreamImageServiceImpl();
        } catch (IllegalStateException e) {
            System.out.println("[Bot] ⚠ 生图服务未启用: " + e.getMessage());
        }

        VisionService vision = null;
        try {
            vision = new DoubaoVisionServiceImpl();
        } catch (IllegalStateException e) {
            System.out.println("[Bot] ⚠ 识图服务未启用: " + e.getMessage());
        }

        // 第 3 步：消息主循环
        System.out.println("\n[Bot] 🟢 开始监听消息...（按 Ctrl+C 退出）\n");

        try {
            while (true) {
                java.util.List<BotMessage> messages = bot.pollMessages();

                for (BotMessage msg : messages) {
                    String userId = msg.userId();

                    // --- 图片消息 → 视觉识别（最高优先级）---
                    if (msg.isImage()) {
                        System.out.println("[收到] " + userId + " : [图片] " + msg.text());
                        handleImage(bot, vision, msg);
                        continue;
                    }

                    // --- 文字消息 ---
                    String text = msg.text().strip();
                    System.out.println("[收到] " + userId + " : " + text);

                    // 尝试匹配生图意图
                    if (tryHandleDraw(bot, ai, imageGen, userId, text)) {
                        continue;
                    }

                    // 尝试匹配天气意图
                    if (tryHandleWeather(bot, ai, weather, userId, text)) {
                        continue;
                    }

                    // 以上都不匹配 → AI 自由对话
                    System.out.println("[Bot] → AI 对话");
                    String reply = ai.chat(text);
                    System.out.println("[回复] " + reply);
                    bot.sendTextWithTyping(userId, reply, 1500L);
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            System.out.println("\n[Bot] 收到退出信号...");
        } finally {
            bot.close();
            IMAGE_EXECUTOR.shutdown();
            try { IMAGE_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            System.out.println("[Bot] 已安全退出。");
        }
    }

    // ---- 图片识别 ----

    private static void handleImage(ILinkBot bot, VisionService vision, BotMessage msg) {
        if (vision == null) {
            bot.sendText(msg.userId(), "图片识别服务未启用，请联系管理员设置 ark.vision.api.key。");
            return;
        }
        String prompt = msg.text().isEmpty() ? null : msg.text();
        System.out.println("[Bot] 👁 检测到图片消息，开始识别...");
        String result = vision.analyze(msg.imageBytes(), prompt);
        System.out.println("[回复] " + result);
        bot.sendTextWithTyping(msg.userId(), result, 1500L);
    }

    // ---- 生图意图（关键词预筛 + AI 精确提取）----

    /**
     * 尝试按生图意图处理一条消息。
     *
     * <p>两段式：先用关键词粗筛，命中后才调 AI 做精确提取。
     * AI 判断不是生图或提取失败时优雅降级。
     *
     * @return {@code true} 如果消息被生图流程消费（不再继续路由）
     */
    private static boolean tryHandleDraw(ILinkBot bot, AiService ai,
                                          ImageGenService imageGen,
                                          String userId, String text) {
        // 第 1 层：关键词粗筛 — 完全不沾边的直接跳过，避免无意义的 AI 调用
        if (!containsKeyword(text, DRAW_KEYWORDS)) {
            return false;
        }

        // 第 2 层：AI 精确提取
        System.out.println("[Bot] 🎨 疑似生图请求，AI 分析中...");
        DrawIntent intent = ai.extractDrawIntent(text);

        if (!intent.isDraw()) {
            // AI 判断不是生图（关键词误匹配，如"这个图不错"）
            System.out.println("[Bot] → AI 判断非生图，转普通对话");
            return false;
        }

        if (intent.promptIsEmpty()) {
            // AI 判断是生图但没有提取到有效描述
            bot.sendText(userId,
                "你想画什么呀？比如可以说「帮我画一只在屋顶看星星的橘猫」～");
            return true;
        }

        // 确认是生图请求，执行生图
        if (imageGen == null) {
            bot.sendText(userId, "生图服务未启用，请联系管理员设置 ark.api.key。");
            return true;
        }

        bot.sendText(userId, "🎨 正在生成图片，请稍候（通常需要 10~30 秒）...");

        // 异步生图 — 不阻塞主循环，在此期间用户可以继续发消息
        final String prompt = intent.prompt();
        IMAGE_EXECUTOR.submit(() -> {
            try {
                byte[] imageBytes = imageGen.generate(prompt);
                bot.sendImage(userId, imageBytes, "generated.png", "「" + prompt + "」");
                System.out.println("[生图] 已发送: " + prompt);
            } catch (Exception e) {
                System.err.println("[生图] ❌ 失败: " + e.getMessage());
                bot.sendText(userId, "抱歉，图片生成失败：" + e.getMessage());
            }
        });
        return true;
    }

    // ---- 天气意图（关键词预筛 + AI 精确提取）----

    /**
     * 尝试按天气查询意图处理一条消息。
     *
     * <p>两段式：先用关键词粗筛，命中后才调 AI 做精确提取。
     *
     * @return {@code true} 如果消息被天气流程消费（不再继续路由）
     */
    private static boolean tryHandleWeather(ILinkBot bot, AiService ai,
                                             WeatherBotService weather,
                                             String userId, String text) {
        // 第 1 层：关键词粗筛
        if (!containsKeyword(text, WEATHER_KEYWORDS)) {
            return false;
        }

        // 第 2 层：AI 精确提取
        System.out.println("[Bot] 🌤 疑似天气查询，AI 分析中...");
        WeatherIntent intent = ai.extractWeatherIntent(text);

        if (!intent.isWeather()) {
            // AI 判断不是天气查询（如"今天心情很好"命中了"晴天"但实际不是问天气）
            System.out.println("[Bot] → AI 判断非天气，转普通对话");
            return false;
        }

        if (intent.cityIsEmpty()) {
            // 问了天气但没指定城市
            bot.sendText(userId, "你想查哪个城市的天气呢？告诉我城市名就好～");
            return true;
        }

        // 确认是天气请求，执行查询
        if (weather == null) {
            bot.sendText(userId, "天气服务未配置，请在 config.properties 中设置 qweather.api.key。");
            return true;
        }

        String reply = weather.query(intent.city());
        System.out.println("[回复] " + reply);
        bot.sendTextWithTyping(userId, reply, 1500L);
        return true;
    }

    // ---- 工具方法 ----

    /** 检查文本是否包含任意一个关键词 */
    private static boolean containsKeyword(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
