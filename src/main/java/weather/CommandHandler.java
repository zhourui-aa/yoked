package weather;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandHandler {
    private final WeatherService weatherService;
    private AiService aiService;
    private final VoiceService voiceService;
    private final List<Tool> tools; // 新增：注册给 AI 的工具列表

    private static final Map<String, String> AVAILABLE_MODELS = new HashMap<>();
    static {
        AVAILABLE_MODELS.put("qwen-plus", "通义千问-Plus（均衡推荐）");
        AVAILABLE_MODELS.put("qwen-max", "通义千问-Max（最强能力）");
        AVAILABLE_MODELS.put("qwen-turbo", "通义千问-Turbo（快速便宜）");
        AVAILABLE_MODELS.put("qwen-vl-plus", "通义千问-VL（图片识别）");
        AVAILABLE_MODELS.put("deepseek-v4-flash", "DeepSeek-V4-Flash（阿里直供）");
        AVAILABLE_MODELS.put("deepseek-v4-pro", "DeepSeek-V4-Pro（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.7-code", "Kimi-K2.7-Code（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.6", "Kimi-K2.6（阿里直供）");
        AVAILABLE_MODELS.put("glm-5.2", "智谱 GLM-5.2（1M上下文/编程强）");
        AVAILABLE_MODELS.put("wan2.7-image", "通义万相 2.7（文生图）");
        AVAILABLE_MODELS.put("wan2.7-image-pro", "通义万相 2.7 Pro（高质量）");
    }

    public CommandHandler(WeatherService ws, AiService ai, VoiceService vs) {
        this.weatherService = ws;
        this.aiService = ai;
        this.voiceService = vs;
        // 注册工具：AI 在对话中可自动调用
        this.tools = List.of(new WeatherTool(ws));
    }

    public String handle(String text) {
        String reply = handleModelCommand(text);
        if (reply != null) return reply;

        return processCommand(text);
    }

    public AiService getAiService() {
        return aiService;
    }

    // ========== 模型/开关命令 ==========

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
            sb.append("💡 例：模型 qwen-vl-plus");
            return sb.toString();
        }

        if (lower.equals("当前模型") || lower.equals("model")) {
            return "🤖 当前模型: " + aiService.getModelName() + "\n发送「模型列表」查看所有模型";
        }

        if (lower.startsWith("模型 ") || lower.startsWith("model ")) {
            String modelName = lower.startsWith("模型 ") ? text.substring(3).trim() : text.substring(6).trim();
            if (!AVAILABLE_MODELS.containsKey(modelName)) {
                return "❌ 未知模型: " + modelName + "\n发送「模型列表」查看可用模型";
            }
            try {
                aiService = new AiService(modelName);
                return "✅ 已切换到模型: " + modelName + "\n描述: " + AVAILABLE_MODELS.get(modelName);
            } catch (Exception e) {
                return "❌ 切换失败: " + e.getMessage();
            }
        }

        if (lower.equals("语音回复 开") || lower.equals("语音开启")) {
            voiceService.setVoiceReplyEnabled(true);
            return "🔊 语音回复已开启";
        }
        if (lower.equals("语音回复 关") || lower.equals("语音关闭")) {
            voiceService.setVoiceReplyEnabled(false);
            return "🔇 语音回复已关闭";
        }

        if (lower.equals("音色列表") || lower.equals("voices")) {
            return voiceService.getVoiceListText();
        }

        if (lower.equals("当前音色") || lower.equals("voice")) {
            return "🎙️ 当前音色: " + voiceService.getCurrentVoice() + "\n发送「音色列表」查看所有音色";
        }

        if (lower.startsWith("音色 ") || lower.startsWith("voice ")) {
            String voiceName = lower.startsWith("音色 ") ? text.substring(3).trim() : text.substring(6).trim();
            try {
                voiceService.setCurrentVoice(voiceName);
                return "✅ 已切换音色: " + voiceService.getCurrentVoice();
            } catch (IllegalArgumentException e) {
                return "❌ " + e.getMessage() + "\n发送「音色列表」查看可用音色";
            }
        }

        return null;
    }

    // ========== 业务命令 ==========

    private String processCommand(String text) {
        String lower = text.toLowerCase().trim();

        if (lower.equals("help") || lower.equals("帮助")) {
            return "🤖 AI 天气机器人\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "🌤️ 天气 北京 → 快速查询天气\n" +
                    "🖼️ 发送图片 → AI识别图片\n" +
                    "🎨 画 xxx → AI生成图片\n" +
                    "💬 任意文字 → AI智能对话（自动识别天气意图）\n" +
                    "📋 模型列表 → 查看可用模型\n" +
                    "🔧 模型 xxx → 切换模型\n" +
                    "📌 当前模型 → 查看当前模型\n" +
                    "🔊 语音开启/语音关闭 → 开关语音回复\n" +
                    "🎙️ 音色列表 → 查看可用音色\n" +
                    "🎙️ 音色 xxx → 切换TTS音色\n" +
                    "📎 发送文档 → AI自动总结\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 现在可以直接问：「明天去北京出差要带伞吗？」\n" +
                    "💡 AI 会自动调用天气工具并给出建议";
        }

        // 快速路径：明确的天气命令，直接查询（省 token、响应快）
        if (lower.startsWith("天气 ")) {
            return queryWeather(text.substring(3).trim(), false);
        }
        if (lower.startsWith("weather ")) {
            return queryWeather(text.substring(8).trim(), false);
        }

        // 智能路径：走 AI + Function Calling
        // AI 会自动判断是否需要调用 weather_query 工具
        return chatWithAi(text);
    }

    private String queryWeather(String city, boolean useAi) {
        System.out.println("🌤️ 快速查询天气: " + city);
        if (city == null || city.isEmpty()) {
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

    /**
     * 走 AI 路径，支持 Function Calling 自动调用工具
     */
    private String chatWithAi(String message) {
        System.out.println("🧠 AI 对话: " + message);
        try {
            return aiService.chatWithTools(message, tools);
        } catch (Exception e) {
            System.err.println("❌ AI 对话失败: " + e.getMessage());
            return "❌ AI 服务暂时不可用: " + e.getMessage();
        }
    }
}