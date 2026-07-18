package com.weather;

import com.weather.exception.WeatherException;
import com.weather.service.WeatherService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Scanner;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 天气查询命令行工具 — 基于和风天气 API。
 *
 * <h3>内置命令（中英文均支持）</h3>
 * <ul>
 *   <li>{@code help} / {@code 帮助} — 显示帮助信息</li>
 *   <li>{@code version} / {@code 版本} — 显示版本信息</li>
 *   <li>{@code status} / {@code 状态} — 显示程序运行状态及 API 配置信息</li>
 *   <li>{@code weather <城市名>} / {@code 天气 <城市名>} — 查询城市天气</li>
 *   <li>{@code exit} / {@code quit} / {@code 退出} — 退出程序</li>
 * </ul>
 *
 * <h3>API Key 配置</h3>
 * 在项目根目录的 {@code config.properties} 文件中填写和风天气 API Key。
 * 也支持通过环境变量 {@code QWEATHER_API_KEY} 或系统属性 {@code qweather.api.key} 设置。
 */
public class WeatherApp {

    private static final String APP_NAME    = "天气查询 CLI";
    private static final String APP_VERSION = "1.0.0";
    private static final String APP_AUTHOR  = "Weather Team";

    /** API Key 状态消息，供 status 命令展示 */
    private static String apiKeyStatus = "未知";
    /** 当前使用的 API Host */
    private static String weatherHost = "未配置";

    private static int queryCount  = 0;
    private static int errorCount  = 0;

    private final WeatherService weatherService;

    public WeatherApp(String apiKey, String weatherHost) {
        this.weatherService = new WeatherService(apiKey, weatherHost);
    }

    public static void main(String[] args) {
        // 加载配置
        String apiKey = loadApiKey();

        WeatherApp app = new WeatherApp(apiKey, weatherHost);

        // 如果命令行传入了参数，直接处理
        if (args.length > 0) {
            String cmd = args[0].toLowerCase().strip();
            if ((cmd.equals("weather") || cmd.equals("天气")) && args.length >= 2) {
                app.executeQuery(args[1]);
            } else {
                app.dispatch(String.join(" ", args));
            }
            return;
        }

        // 否则启动交互式命令行
        app.runRepl();
    }

    // ---- API Key 加载 ----------------------------------------------------------

    /**
     * 按优先级加载 API Key：
     * <ol>
     *   <li>系统属性 {@code qweather.api.key}</li>
     *   <li>环境变量 {@code QWEATHER_API_KEY}</li>
     *   <li>配置文件 {@code config.properties}（项目根目录）</li>
     * </ol>
     */
    static String loadApiKey() {
        // 1. 系统属性
        String key = System.getProperty("qweather.api.key");
        if (key != null && !key.isBlank()) {
            apiKeyStatus = "已配置（来源：系统属性 -Dqweather.api.key）";
            return key;
        }

        // 2. 环境变量
        key = System.getenv("QWEATHER_API_KEY");
        if (key != null && !key.isBlank()) {
            apiKeyStatus = "已配置（来源：环境变量 QWEATHER_API_KEY）";
            return key;
        }

        // 3. config.properties 文件
        Path configPath = Paths.get("config.properties");
        if (Files.exists(configPath)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));

                // 读取 API Host
                String host = props.getProperty("qweather.api.host");
                if (host != null && !host.isBlank() && !host.startsWith("请在此填入")) {
                    weatherHost = host.strip();
                }

                key = props.getProperty("qweather.api.key");
                if (key != null && !key.isBlank() && !key.startsWith("请在此填入")) {
                    apiKeyStatus = "已配置（来源：config.properties）";
                    return key;
                }
            } catch (IOException e) {
                apiKeyStatus = "读取 config.properties 失败: " + e.getMessage();
                return null;
            }
            apiKeyStatus = "未配置（config.properties 中尚未填入 API Key）";
            return null;
        } else {
            apiKeyStatus = "未配置（config.properties 文件不存在）";
            return null;
        }
    }

    // ---- 交互式主循环 ----------------------------------------------------------

    private void runRepl() {
        printBanner();

        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                System.out.print("\n天气> ");
                String input = scanner.nextLine().strip();

                if (input.isEmpty()) continue;

                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")
                        || input.equals("退出")) {
                    System.out.println("再见！共查询 " + queryCount + " 次，错误 " + errorCount + " 次");
                    break;
                }

                try {
                    dispatch(input);
                } catch (Exception e) {
                    errorCount++;
                    logError("未预期的错误: " + e.getMessage(), e);
                }
            }
        }
    }

    // ---- 命令分发 --------------------------------------------------------------

    private void dispatch(String input) {
        String lower = input.toLowerCase().strip();

        // 内置命令
        if (lower.equals("help") || lower.equals("?") || lower.equals("-h")
                || lower.equals("--help") || lower.equals("帮助")) {
            showHelp();
            return;
        }
        if (lower.equals("version") || lower.equals("-v") || lower.equals("--version")
                || lower.equals("版本")) {
            showVersion();
            return;
        }
        if (lower.equals("status") || lower.equals("状态")) {
            showStatus();
            return;
        }

        // weather <城市名>  或  天气 <城市名>
        if (lower.startsWith("weather ")) {
            String city = input.substring("weather ".length()).strip();
            executeQuery(city);
            return;
        }
        if (lower.startsWith("天气 ")) {
            String city = input.substring("天气 ".length()).strip();
            executeQuery(city);
            return;
        }

        // 只输入 "weather" 或 "天气" 但没有城市名 → 报错
        if (lower.equals("weather") || lower.equals("天气")) {
            System.err.println("❌  错误：请输入城市名。");
            System.err.println("   用法：weather <城市名>  或  天气 <城市名>");
            errorCount++;
            return;
        }

        // 如果用户直接输入城市名（快捷方式）
        if (!lower.contains(" ")) {
            executeQuery(input);
            return;
        }

        System.out.println("未知命令：" + input);
        System.out.println("输入 'help' 或 '帮助' 查看可用命令。");
    }

    // ---- 命令实现 --------------------------------------------------------------

    private void showHelp() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  " + APP_NAME + " v" + APP_VERSION + " — 帮助");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println();
        System.out.println("  命令列表");
        System.out.println("  ────────");
        System.out.println("  help / 帮助              显示本帮助信息");
        System.out.println("  version / 版本           显示程序版本");
        System.out.println("  status / 状态            显示程序运行状态及 API 配置");
        System.out.println("  weather <城市名>         查询指定城市的实时天气");
        System.out.println("  天气 <城市名>             同上（中文命令）");
        System.out.println("  <城市名>                 快捷方式 — 直接输入城市名查询");
        System.out.println("  exit / quit / 退出       退出程序");
        System.out.println();
        System.out.println("  使用示例");
        System.out.println("  ────────");
        System.out.println("  weather 北京              → 查询北京的天气");
        System.out.println("  weather London            → 查询伦敦的天气");
        System.out.println("  天气 上海                  → 使用中文命令查询上海天气");
        System.out.println("  东京                      → 快捷方式查询东京天气");
        System.out.println();
        System.out.println("  API 配置");
        System.out.println("  ────────");
        System.out.println("  在项目根目录的 config.properties 中填入你的和风天气 API Key。");
        System.out.println("  获取方式：https://dev.qweather.com/ → 注册 → 控制台 → 创建项目");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════");
    }

    private void showVersion() {
        System.out.println(APP_NAME + " v" + APP_VERSION);
        System.out.println("作者     : " + APP_AUTHOR);
        System.out.println("API 服务 : 和风天气 (QWeather)");
        System.out.println("Java     : " + System.getProperty("java.version"));
        System.out.println("操作系统 : " + System.getProperty("os.name"));
    }

    private void showStatus() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        System.out.println("══════════════════════════════");
        System.out.println("  程序运行状态");
        System.out.println("══════════════════════════════");
        System.out.println("  程序名称 : " + APP_NAME);
        System.out.println("  版本号   : " + APP_VERSION);
        System.out.println("  已查询   : " + queryCount + " 次");
        System.out.println("  错误次数 : " + errorCount);
        System.out.println("  当前时间 : " + now);
        System.out.println("──────────────────────────────");
        System.out.println("  API 服务 : 和风天气 QWeather");
        System.out.println("  API Host : " + weatherHost);
        System.out.println("  API Key  : " + apiKeyStatus);
        System.out.println("══════════════════════════════");
    }

    // ---- 天气查询 --------------------------------------------------------------

    private void executeQuery(String city) {
        // 检查：城市名为空
        if (city == null || city.isBlank()) {
            errorCount++;
            System.err.println("❌  错误：请输入城市名。");
            System.err.println("   用法：weather <城市名>  或  天气 <城市名>");
            return;
        }

        try {
            var data = weatherService.query(city);
            queryCount++;
            System.out.println(data);
        } catch (WeatherException e) {
            errorCount++;
            System.err.println("❌  错误：" + e.getMessage());
            logError("查询城市 \"" + city + "\" 时发生异常", e);
        }
    }

    // ---- 辅助方法 --------------------------------------------------------------

    private void printBanner() {
        System.out.println();
        System.out.println(" ╔══════════════════════════════════════╗");
        System.out.println(" ║     🌤   " + APP_NAME + " v" + APP_VERSION + "      ║");
        System.out.println(" ║   输入 'help' 或 '帮助' 查看命令      ║");
        System.out.println(" ║   输入 'exit' 或 '退出' 退出程序      ║");
        System.out.println(" ╚══════════════════════════════════════╝");
    }

    private void logError(String msg, Throwable t) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        System.err.println("[" + ts + "] [错误] " + msg);
        if (t != null) {
            System.err.println("[" + ts + "] [错误] 原因: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
