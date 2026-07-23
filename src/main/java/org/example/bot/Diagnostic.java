package org.example.bot;

import org.example.bot.impl.*;
import org.example.bot.service.*;
import org.example.bot.util.ConfigUtil;

/**
 * 诊断工具 — 逐一测试每个工具类，打印可用状态。
 * 运行方式：mvn.cmd exec:java -Dexec.mainClass="org.example.bot.Diagnostic"
 */
public class Diagnostic {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  yoked-main 工具诊断报告");
        System.out.println("=".repeat(60));

        // 1. 环境变量检查
        System.out.println("\n--- 1. 环境变量 ---");
        String deepseekEnv = System.getenv("DEEPSEEK_API_KEY");
        System.out.println("  DEEPSEEK_API_KEY = " + mask(deepseekEnv));

        // 2. ConfigUtil 读取测试
        System.out.println("\n--- 2. ConfigUtil 读取配置 ---");
        testConfig("deepseek.api.key", "DEEPSEEK_API_KEY");
        testConfig("qweather.api.key", "QWEATHER_API_KEY");
        testConfig("qweather.api.host", "QWEATHER_API_HOST");
        testConfig("ark.api.key", "ARK_API_KEY");
        testConfig("ark.vision.api.key", "ARK_VISION_API_KEY");
        testConfig("dashscope.api.key", "DASHSCOPE_API_KEY");
        testConfig("datetime.api.key", "DATETIME_API_KEY");

        // 3. DeepSeek AI
        System.out.println("\n--- 3. DeepSeek AI 服务 ---");
        try {
            DeepSeekAiServiceImpl ai = new DeepSeekAiServiceImpl(
                "你是一个测试助手。", "只回复OK");
            System.out.println("  ✅ 创建成功");
            try {
                String reply = ai.chat("test", "回复OK");
                System.out.println("  ✅ AI 对话测试: " + reply);
            } catch (Exception e) {
                System.out.println("  ❌ AI 对话失败: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("  ❌ 创建失败: " + e.getMessage());
        }

        // 4. 天气服务
        System.out.println("\n--- 4. 天气服务 ---");
        try {
            WeatherBotService weather = WeatherBotService.create();
            if (weather != null) {
                System.out.println("  ✅ 创建成功");
                String result = weather.query("北京");
                System.out.println("  ⏺ 查询结果: " + (result.length() > 60 ? result.substring(0, 60) + "..." : result));
            } else {
                System.out.println("  ❌ 创建失败: 返回 null（配置缺失）");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 异常: " + e.getMessage());
        }

        // 5. 生图服务
        System.out.println("\n--- 5. 生图服务 (Seedream) ---");
        try {
            ImageGenService imgGen = new SeedreamImageServiceImpl();
            System.out.println("  ✅ 创建成功");
        } catch (IllegalStateException e) {
            System.out.println("  ⚠️ 未启用: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  ❌ 异常: " + e.getMessage());
        }

        // 6. 识图服务
        System.out.println("\n--- 6. 识图服务 (Doubao Vision) ---");
        try {
            VisionService vision = new DoubaoVisionServiceImpl();
            System.out.println("  ✅ 创建成功");
        } catch (IllegalStateException e) {
            System.out.println("  ⚠️ 未启用: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  ❌ 异常: " + e.getMessage());
        }

        // 7. 语音合成服务
        System.out.println("\n--- 7. 语音合成服务 (Qwen TTS) ---");
        try {
            SpeechService tts = new QwenTtsSpeechServiceImpl();
            System.out.println("  ✅ 创建成功");
            System.out.println("  ⏺ 音色列表: " + tts.listVoices().substring(0, 80) + "...");
        } catch (IllegalStateException e) {
            System.out.println("  ⚠️ 未启用: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  ❌ 异常: " + e.getMessage());
        }

        // 8. 日期时间服务
        System.out.println("\n--- 8. 日期时间服务 ---");
        try {
            DateTimeService dt = new DateTimeServiceImpl();
            System.out.println("  ✅ 创建成功");
            String result = dt.query("Asia/Shanghai");
            System.out.println("  ⏺ 上海时间: " + result);
        } catch (Exception e) {
            System.out.println("  ❌ 异常: " + e.getMessage());
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  诊断完成");
        System.out.println("=".repeat(60));
    }

    private static void testConfig(String propKey, String envKey) {
        String val = ConfigUtil.get(propKey, envKey);
        System.out.println("  " + propKey + " = " + mask(val));
    }

    private static String mask(String val) {
        if (val == null) return "(未配置)";
        if (val.length() <= 8) return val;
        return val.substring(0, 4) + "****" + val.substring(val.length() - 4);
    }
}
