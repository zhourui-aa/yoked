package weather;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ILinkWeatherBot {

    private final WeatherService weatherService;
    private AiService aiService;
    private ILinkClient client;

    // 可用模型列表（根据阿里百炼模型广场更新）
    private static final Map<String, String> AVAILABLE_MODELS = new HashMap<>();
    static {
        // 通义千问系列
        AVAILABLE_MODELS.put("qwen-plus", "通义千问-Plus（均衡推荐）");
        AVAILABLE_MODELS.put("qwen-max", "通义千问-Max（最强能力）");
        AVAILABLE_MODELS.put("qwen-turbo", "通义千问-Turbo（快速便宜）");
        AVAILABLE_MODELS.put("qwen-coder-plus", "通义千问-Coder（编程专用）");
        AVAILABLE_MODELS.put("qwen-vl-plus", "通义千问-VL（图片识别）");

        // DeepSeek 系列（阿里直供）
        AVAILABLE_MODELS.put("deepseek-v4-flash", "DeepSeek-V4-Flash（阿里直供）");
        AVAILABLE_MODELS.put("deepseek-v4-pro", "DeepSeek-V4-pro（更强 阿里直供）");
        // Kimi 系列（阿里直供）
        AVAILABLE_MODELS.put("kimi-k2.7-code", "Kimi-K2.7-Code（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.6", "Kimi-K2.6（阿里直供）");

    }

    public ILinkWeatherBot() {
        this.weatherService = new WeatherService();
        this.aiService = new AiService("qwen-plus");
    }

    public void start() throws Exception {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .heartbeatEnabled(true)
                .build();

        this.client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("✅ 登录成功！botId = " + context.getBotId());
                        System.out.println("🤖 AI 天气机器人已启动");
                        System.out.println("👉 发送 help 查看所有命令");
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("❌ 登录失败: " + throwable.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        handleMessages(messages);
                    }
                })
                .build();

        System.out.println("请扫码登录：");
        String qrCode = client.executeLogin();
        System.out.println(qrCode);

        LoginContext context = client.getLoginFuture().get();
        System.out.println("🎉 登录完成！");

        while (!client.isStopped()) {
            Thread.sleep(1000);
        }
    }

    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String fromUserId = msg.getFrom_user_id();
            if (msg.getItem_list() == null) continue;

            for (MessageItem item : msg.getItem_list()) {
                if (item.getText_item() == null) continue;

                String text = item.getText_item().getText().trim();
                System.out.println("📨 [" + fromUserId + "]: " + text);

                String reply = handleModelCommand(text);
                if (reply == null) {
                    reply = processCommand(text);
                }

                if (reply != null) {
                    try {
                        client.sendText(fromUserId, reply);
                        System.out.println("✅ 回复成功");
                    } catch (Exception e) {
                        System.err.println("❌ 发送失败: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 处理模型切换命令
     */
    private String handleModelCommand(String text) {
        String lower = text.toLowerCase().trim();

        if (lower.equals("模型列表") || lower.equals("models")) {
            StringBuilder sb = new StringBuilder();
            sb.append("📋 可用模型列表（").append(AVAILABLE_MODELS.size()).append("个）\n");
            sb.append("━━━━━━━━━━━━━━━\n");
            for (Map.Entry<String, String> entry : AVAILABLE_MODELS.entrySet()) {
                sb.append("• ").append(entry.getKey()).append("\n");
                sb.append("  → ").append(entry.getValue()).append("\n");
            }
            sb.append("━━━━━━━━━━━━━━━\n");
            sb.append("💡 发送「模型 名称」切换\n");
            sb.append("💡 例：模型 deepseek-v4-flash");
            return sb.toString();
        }

        if (lower.equals("当前模型") || lower.equals("model")) {
            return "🤖 当前模型: " + aiService.getModelName() + "\n" +
                    "发送「模型列表」查看所有模型";
        }

        if (lower.startsWith("模型 ") || lower.startsWith("model ")) {
            String modelName;
            if (lower.startsWith("模型 ")) {
                modelName = text.substring(3).trim();
            } else {
                modelName = text.substring(6).trim();
            }

            if (!AVAILABLE_MODELS.containsKey(modelName)) {
                return "❌ 未知模型: " + modelName + "\n" +
                        "发送「模型列表」查看可用模型\n" +
                        "💡 提示：阿里百炼上所有模型都可以尝试，" +
                        "如果模型不在列表中，可以直接修改代码添加";
            }

            try {
                aiService = new AiService(modelName);
                return "✅ 已切换到模型: " + modelName + "\n" +
                        "描述: " + AVAILABLE_MODELS.get(modelName);
            } catch (Exception e) {
                return "❌ 切换失败: " + e.getMessage();
            }
        }

        return null;
    }

    private String processCommand(String text) {
        String lower = text.toLowerCase().trim();

        if (lower.equals("help") || lower.equals("帮助")) {
            return "🤖 AI 天气机器人\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "🌤️ 天气 北京 → 查询天气\n" +
                    "💬 任意文字 → AI对话\n" +
                    "📋 模型列表 → 查看可用模型\n" +
                    "🔧 模型 xxx → 切换模型\n" +
                    "📌 当前模型 → 查看当前模型\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 支持模型：qwen/deepseek/kimi 等";
        }

        if (lower.startsWith("天气 ")) {
            String city = text.substring(3).trim();
            return queryWeather(city, false);
        }

        if (lower.startsWith("weather ")) {
            String city = text.substring(8).trim();
            return queryWeather(city, false);
        }

        if (lower.contains("天气") && (lower.contains("怎么样") || lower.contains("如何"))) {
            String city = extractCityFromText(text);
            if (city != null) {
                return queryWeather(city, true);
            }
        }

        return chatWithAi(text);
    }

    private String queryWeather(String city, boolean useAi) {
        System.out.println("🌤️ 查询天气: " + city + (useAi ? " (AI分析)" : ""));
        if (city.isEmpty()) {
            return "❌ 请提供城市名称";
        }

        try {
            WeatherInfo info = weatherService.getCurrentWeather(city);
            if (useAi) {
                return aiService.analyzeWeather(city, info.format());
            } else {
                return info.format();
            }
        } catch (Exception e) {
            System.err.println("❌ 天气查询失败: " + e.getMessage());
            return "❌ 天气查询失败: " + e.getMessage();
        }
    }

    private String chatWithAi(String message) {
        System.out.println("🧠 AI对话: " + message);
        try {
            return aiService.chat(message);
        } catch (Exception e) {
            System.err.println("❌ AI对话失败: " + e.getMessage());
            return "❌ AI服务暂时不可用: " + e.getMessage();
        }
    }

    private String extractCityFromText(String text) {
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "重庆",
                "天津", "苏州", "长沙", "郑州", "沈阳", "青岛", "宁波", "东莞", "无锡", "佛山"};
        for (String city : cities) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    public void stop() {
        System.out.println("🛑 正在关闭...");
        if (client != null) {
            client.close();
        }
    }

    public static void main(String[] args) {
        ILinkWeatherBot bot = new ILinkWeatherBot();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n收到关闭信号...");
            bot.stop();
        }));

        try {
            bot.start();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}