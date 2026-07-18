package com.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.weather.exception.WeatherException;
import com.weather.model.WeatherResponse;
import com.weather.service.ImageAnalyzer;
import com.weather.service.ImageGenerator;
import com.weather.service.WeatherService;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WechatWeatherBot {
    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(WechatWeatherBot.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static AIClient aiClient = new AIClient();
    private static final ImageAnalyzer imageAnalyzer = new ImageAnalyzer();
    private static final ImageGenerator imageGenerator = new ImageGenerator();
    /** 已处理的消息 ID，用于 onMessage 和 getUpdates 双路去重 */
    private static final Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        WeatherService weatherService;
        try {
            weatherService = new WeatherService();
            log.info("天气服务初始化成功");
        } catch (Exception e) {
            log.error("天气服务初始化失败，请检查 application.properties", e);
            System.out.println("天气服务启动失败: " + e.getMessage());
            return;
        }

        final ILinkClient[] clientRef = new ILinkClient[1];

        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .heartbeatEnabled(true)
                        .heartbeatIntervalMs(30000)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("登录成功！botId: {}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable e) {
                        log.error("登录失败", e);
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            try {
                                handleMessage(clientRef[0], weatherService, aiClient, msg);
                            } catch (Exception e) {
                                System.err.println("[onMessage] 异常: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                })
                .build();
        clientRef[0] = client;

        // 登录
        String qrContent = client.executeLogin();
        System.out.println("请复制以下链接到浏览器打开，用微信扫码登录：");
        System.out.println(qrContent);

        LoginContext loginCtx = client.getLoginFuture().get();
        System.out.println("登录成功，Bot 已就绪！");

        // ===== 独立轮询线程：每秒拉取一次，保证快速响应 =====
        Thread pollThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<WeixinMessage> messages = clientRef[0].getUpdates();
                    for (WeixinMessage msg : messages) {
                        try {
                            handleMessage(clientRef[0], weatherService, aiClient, msg);
                        } catch (Exception e) {
                            System.err.println("[轮询] handleMessage 异常: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[轮询] getUpdates 异常: " + e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "poll-thread");
        pollThread.setDaemon(true);
        pollThread.start();

        // ===== 定时清理去重缓存，防止内存泄漏 =====
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(300_000); // 每 5 分钟
                } catch (InterruptedException e) {
                    break;
                }
                int size = processedMsgIds.size();
                processedMsgIds.clear();
                System.out.println("[清理] 已清空去重缓存（清除 " + size + " 条）");
            }
        }, "cleanup-thread");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        System.out.println("消息监听已启动 (onMessage推送 + 1秒轮询 + message_id去重)");
        System.out.println("请用微信给 Bot 发消息...");

        // 主线程 keep-alive
        // noinspection InfiniteLoopStatement
        while (true) {
            Thread.sleep(60000);
        }
    }

    private static void trySendText(ILinkClient client, String toUser, String text) {
        try {
            client.sendText(toUser, text);
        } catch (Exception e) {
            System.err.println("[发送失败] " + e.getMessage());
        }
    }

    private static void handleMessage(ILinkClient client,
                                      WeatherService weatherService,
                                      AIClient aiClient,
                                      WeixinMessage msg) throws IOException {

        // ===== message_id 去重：两条路径共用同一个 Set，原子操作保证不重复 =====
        Long msgId = msg.getMessage_id();
        if (msgId != null && !processedMsgIds.add(msgId)) {
            // add() 返回 false = Set 中已存在，说明另一条路径已经处理过了
            return;
        }

        String fromUser = msg.getFrom_user_id();
        System.out.println(">>> 处理消息 msgId=" + msgId + " from=" + fromUser);

        if (msg.getItem_list() == null) {
            System.out.println("!!! item_list 为空");
            return;
        }

        for (MessageItem item : msg.getItem_list()) {
            // ===== 图片消息处理（独立判断，不依赖 text_item） =====
            if (item.getImage_item() != null) {
                System.out.println("[图片] 收到来自 " + fromUser + " 的图片");
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    String result = imageAnalyzer.analyze(imageBytes, "请详细描述这张图片的内容");
                    System.out.println("[图片] AI 分析结果: " + result);
                    trySendText(client, fromUser, result);
                } catch (Exception e) {
                    System.err.println("[图片] 处理失败: " + e.getMessage());
                    trySendText(client, fromUser, "抱歉，图片识别失败: " + e.getMessage());
                }
            }

            // ===== 文本消息处理 =====
            if (item.getText_item() == null) {
                continue;
            }

            String text = item.getText_item().getText().trim();
            // ====== 新增：图片生成关键词前置判断 ======
            if (text.contains("生成图") || text.contains("画一张") || text.contains("画个")
                    || text.contains("生成一张") || text.contains("天气图")) {

                // 提取图片描述
                String imageDesc = text.replaceAll(".*?(生成图|画一张|画个|生成一张|天气图|画一个|做一张|来一张|生成图片)\\s*", "").trim();
                if (imageDesc.isEmpty()) {
                    imageDesc = "天气预报主题插画，晴朗天气";
                }

                System.out.println("[图片生成] 描述: " + imageDesc);
                try {
                    // 先告诉用户正在生成
                    trySendText(client, fromUser, "正在为你生成图片，请稍等...");

                    byte[] imageBytes = imageGenerator.generate(imageDesc);
                    client.sendImage(fromUser, imageBytes, "generated.png", "image/png");
                    trySendText(client, fromUser, "图片已生成~");

                } catch (Exception e) {
                    System.err.println("[图片生成] 失败: " + e.getMessage());
                    trySendText(client, fromUser, "抱歉，图片生成失败: " + e.getMessage());
                }
                return;  // ← 已经处理完，直接返回，不走后面的天气逻辑
            }

            System.out.println(">>> 文本: [" + text + "]");

            String reply;
            try {
                // 第1步：AI 分析意图
                String aiResult = aiClient.analyzeIntent(text);

                // 清洗 markdown 代码块
                String cleanJson = aiResult.trim();
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.replaceAll("```json\\s*", "")
                            .replaceAll("```\\s*", "")
                            .trim();
                }

                JsonNode intent = objectMapper.readTree(cleanJson);

                if (intent.path("isWeather").asBoolean(false)) {
                    String city = intent.path("city").asText();

                    WeatherResponse weather = weatherService.queryByCity(city);

                    String weatherStr = String.format(
                            "城市:%s 温度:%s°C 体感:%s°C 天气:%s 风向:%s %s级 湿度:%s%% 气压:%shPa",
                            weather.getCityName(), weather.getNow().getTemp(),
                            weather.getNow().getFeelsLike(), weather.getNow().getText(),
                            weather.getNow().getWindDir(), weather.getNow().getWindScale(),
                            weather.getNow().getHumidity(), weather.getNow().getPressure()
                    );
                    reply = aiClient.formatWeatherReply(fromUser,text, weatherStr);
                } else {
                    reply = aiClient.chat(fromUser,text);
                }

            } catch (WeatherException e) {
                reply = "抱歉，" + e.getMessage();
            } catch (Exception e) {
                System.err.println("!!! 处理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                reply = "抱歉，出了点问题，请稍后再试。";
            }

            System.out.println(">>> 回复: " + reply);
            try {
                client.sendText(fromUser, reply);
            } catch (Exception e) {
                System.err.println("!!! sendText 失败: " + e.getMessage());
            }
        }
    }
}
