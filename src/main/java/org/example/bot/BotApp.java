package org.example.bot;

import org.example.bot.ilink.BotCluster;
import org.example.bot.ilink.ILinkBot;
import org.example.bot.model.BotMessage;
import org.example.bot.service.AiService;
import org.example.bot.service.ImageGenService;
import org.example.bot.service.SpeechService;
import org.example.bot.service.VisionService;
import org.example.bot.service.WeatherBotService;
import org.example.bot.impl.DeepSeekAiServiceImpl;
import org.example.bot.impl.DoubaoVisionServiceImpl;
import org.example.bot.impl.QwenTtsSpeechServiceImpl;
import org.example.bot.impl.SeedreamImageServiceImpl;
import org.example.bot.impl.CalculatorServiceImpl;
import org.example.bot.service.CalculatorService;
import org.example.bot.service.RandomService;
import org.example.bot.service.ExpressService;
import org.example.bot.impl.RandomServiceImpl;
import org.example.bot.impl.ExpressServiceImpl;
import org.example.bot.service.NewsService;
import org.example.bot.service.FootballService;
import org.example.bot.service.DietService;
import org.example.bot.service.DateTimeService;
import org.example.bot.service.FinanceService;
import org.example.bot.service.WebReaderService;
import org.example.bot.impl.FinanceServiceImpl;
import org.example.bot.impl.WebReaderServiceImpl;
import org.example.bot.service.WebSearchService;
import org.example.bot.impl.WebSearchServiceImpl;
import org.example.bot.impl.RssNewsServiceImpl;
import org.example.bot.impl.FootballServiceImpl;
import org.example.bot.impl.DietServiceImpl;
import org.example.bot.impl.BotState;
import org.example.bot.service.MusicService;
import org.example.bot.impl.MusicServiceImpl;
import org.example.bot.impl.DateTimeServiceImpl;
import org.example.bot.tools.ToolCenter;
import org.example.bot.tools.ToolCondition;
import org.example.bot.tools.ToolDefinition;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.google.gson.JsonObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;

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

    /** 默认人设 */
    private static final String DEFAULT_PERSONA = "你是一个友好的微信AI助手。";
    /** 技术指令（不随人设变化，始终追加） */
    private static final String TECH_INSTRUCTIONS =
        "你有语音回复能力，用户要求语音时你的文字会自动转语音，所以不要说你不能发语音。" +
        "回复简洁自然，适合朗读。" +
        "你可以查询天气、获取新闻、生成图片、识别图片、总结文件等。" +
        "你可以同时调用多个工具或依次调用工具来满足用户的复杂需求，最终把所有结果整合在一起回复用户。" +
        "当用户问新闻相关问题时，直接调用 get_news 工具获取真实新闻，不要说你没有联网功能。";

    /** 生图专用线程池 — 避免阻塞主消息循环 */
    private static final ExecutorService IMAGE_EXECUTOR =
        Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "image-gen");
            t.setDaemon(true);
            return t;
        });

    /** 日期时间服务 — 始终可用（无 Key 时返回提示） */
    private static final DateTimeService dateTime = new DateTimeServiceImpl();
    private static final MusicService music = new MusicServiceImpl();
    /** 工具中心 — 统一管理所有 FC 工具定义、注册和条件评估 */
    private static final ToolCenter toolCenter = new ToolCenter();
    /** Bot 集群 — 支持多微信号同时在线，运行时可通过命令动态新建 */
    private static BotCluster cluster;

    /** 要创建的 Bot 数量（可通过 -Dbots=3 覆盖） */
    private static final int BOT_COUNT = Integer.getInteger("bots", 1);

    // 语音回复关键词 — 无需 AI 确认，关键词命中即生效
    private static final String[] VOICE_REPLY_KEYWORDS = {
        "语音", "讲话", "说话", "发语音", "用语音", "说给我", "讲给我", "念给我", "读给我"
    };

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 AI 聊天机器人 启动中...（" + BOT_COUNT + " 个 bot）");
        System.out.println("========================================");

        // 第 1 步：创建 Bot 集群（每个 bot 在后台线程生成二维码等待扫码）
        cluster = new BotCluster();
        for (int i = 1; i <= BOT_COUNT; i++) {
            cluster.addBot(BOT_COUNT > 1 ? "bot-" + i : "default");
        }

        // 第 2 步：创建服务
        AiService ai = new DeepSeekAiServiceImpl(DEFAULT_PERSONA, TECH_INSTRUCTIONS);
        WeatherBotService weather = WeatherBotService.create();

        ImageGenService imageGen = null;
        try { imageGen = new SeedreamImageServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 生图服务未启用: " + e.getMessage()); }

        VisionService vision = null;
        try { vision = new DoubaoVisionServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 识图服务未启用: " + e.getMessage()); }

        SpeechService tts = null;
        try { tts = new QwenTtsSpeechServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 语音合成服务未启用: " + e.getMessage()); }

        CalculatorService calc = new CalculatorServiceImpl();

        RandomService random = new RandomServiceImpl();

        ExpressService express = null;
        try { express = new ExpressServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 快递查询服务未启用: " + e.getMessage()); }

        NewsService news = new RssNewsServiceImpl();
        System.out.println("[Bot] 📰 新闻服务已就绪");

        FootballService football = new FootballServiceImpl();
        System.out.println("[Bot] ⚽ 足球数据服务已就绪");

        DietService diet = new DietServiceImpl();
        System.out.println("[Bot] 🥗 饮食推荐服务已就绪");

        FinanceService finance = new FinanceServiceImpl();
        System.out.println("[Bot] 💹 金融行情服务已就绪（股票/基金/加密货币）");

        WebReaderService webReader = new WebReaderServiceImpl();
        System.out.println("[Bot] 📖 网页读取服务已就绪（文章抓取与摘要）");

        WebSearchService search = null;
        try { search = new WebSearchServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 联网搜索服务未启用: " + e.getMessage()); }

        // ---- 向工具中心注册所有 FC 工具 ----
        registerAllTools(ai, weather, calc, random, express, football, diet, imageGen, vision, news, finance, webReader, search);
        System.out.println(toolCenter.summary());

        // ---- 捕获为 final 变量供 lambda 使用 ----
        final ImageGenService fImageGen = imageGen;
        final VisionService fVision = vision;
        final SpeechService fTts = tts;
        final AiService fAi = ai;
        final WeatherBotService fWeather = weather;
        final CalculatorService fCalc = calc;
        final RandomService fRandom = random;
        final ExpressService fExpress = express;
        final NewsService fNews = news;
        final FootballService fFootball = football;
        final DietService fDiet = diet;
        final FinanceService fFinance = finance;
        final WebReaderService fWebReader = webReader;
        final WebSearchService fSearch = search;

        // 第 3 步：注册消息处理器 — 每条消息到达时直接处理
        cluster.setHandler(msg -> {
            String userId = msg.userId();
            ILinkBot bot = BotCluster.current();

            if (msg.isVoice()) {
                System.out.println("[收到] " + userId + " : [语音] "
                    + (msg.voiceText() != null ? msg.voiceText() : ""));
                handleVoice(bot, fAi, fTts, fCalc, fRandom, fExpress, fFootball, fDiet, fWeather, fVision, fImageGen, fNews, fFinance, fWebReader, msg);
                return;
            }
            if (msg.isImage()) {
                System.out.println("[收到] " + userId + " : [图片] " + msg.text());
                handleImage(bot, fAi, fVision, msg);
                return;
            }
            if (msg.isFile()) {
                System.out.println("[收到] " + userId + " : [文件] " + msg.fileName());
                handleFile(bot, fAi, userId, msg);
                return;
            }
            // 文字消息
            String text = msg.text().strip();
            System.out.println("[收到] " + userId + " : " + text);
            processTextMessage(bot, fAi, fTts, fCalc, fRandom, fExpress, fFootball, fDiet, fWeather, fVision, fImageGen, fNews, fFinance, fWebReader,
                               userId, text, false);
        });

        System.out.println("\n[Bot] 🟢 等待扫码登录...（按 Ctrl+C 退出）\n");
        cluster.awaitLogins();
        System.out.println("\n[Bot] 🟢 所有 bot 已登录，开始监听消息...\n");

        // 主线程阻塞，消息由 handler 线程处理
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("\n[Bot] 收到退出信号...");
        } finally {
            cluster.closeAll();
            IMAGE_EXECUTOR.shutdown();
            try { IMAGE_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            System.out.println("[Bot] 已安全退出。");
        }
    }

    // ============================================================
    //  统一文字消息路由
    //  优先级：本地命令 → Function Calling（AI 选工具）→ 自由对话
    // ============================================================

    /**
     * 统一处理文字消息（也用于语音消息的文字部分）。
     *
     * @param forceVoice {@code true} 表示这条消息来自语音输入，回复必须带语音
     */
    private static void processTextMessage(ILinkBot bot, AiService ai, SpeechService tts,
                                           CalculatorService calc,
                                           RandomService random, ExpressService express,
                                           FootballService football, DietService diet,
                                           WeatherBotService weather, VisionService vision,
                                           ImageGenService imageGen, NewsService news,
                                           FinanceService finance,
                                           WebReaderService webReader,
                                           String userId, String text, boolean forceVoice) {
        // ① 本地命令 — 精确/前缀匹配，零 API 消耗
        if (tryHandleLocalCommand(bot, ai, tts, userId, text)) return;

        // ② 语音意图 — 关键词命中即生效（不再额外调 AI 确认）
        boolean wantsVoice = forceVoice
            || (tts != null && containsKeyword(text, VOICE_REPLY_KEYWORDS));
        if (wantsVoice) System.out.println("[Bot] 🔊 语音回复");

        // ③ 构建工具列表 — 根据当前状态动态决定哪些工具可用
        java.util.List<FunctionDefinition> tools = new java.util.ArrayList<>();
        java.util.Map<String, java.util.function.Function<JsonObject, String>> executors
            = new java.util.LinkedHashMap<>();
        buildTools(tools, executors, bot, ai, calc, random, express, football, diet, weather, vision, imageGen, news, finance, webReader, userId);

        // ④ 统一 Function Calling — 一次 API 调用，AI 自主决定用哪个工具
        if (!tools.isEmpty()) {
            String fcResult = ai.chatWithTools(userId, text, tools, executors);
            if (fcResult != null) {
                System.out.println("[回复] " + fcResult);
                bot.sendTextWithTyping(userId, fcResult, 500L);
                if (wantsVoice || isVoiceMode(ai, userId))
                    sendAsVoice(bot, tts, userId, fcResult);
                return;
            }
        }

        // ⑤ 降级：AI 自由对话
        System.out.println("[Bot] → AI 对话");
        String reply = ai.chat(userId, text);
        System.out.println("[回复] " + reply);
        bot.sendTextWithTyping(userId, reply, 500L);
        if (wantsVoice || isVoiceMode(ai, userId))
            sendAsVoice(bot, tts, userId, reply);
    }

    // ---- 本地命令（精确/前缀匹配，不消耗 AI 调用）----

    private static boolean tryHandleLocalCommand(ILinkBot bot, AiService ai, SpeechService tts,
                                                  String userId, String text) {
        // "帮助" / "help"
        if (text.equals("帮助") || text.equalsIgnoreCase("help")) {
            bot.sendText(userId, ai.getHelpMessage());
            System.out.println("[回复] 帮助面板");
            return true;
        }

        // "设定人设xxx" / "人设xxx"
        if (text.startsWith("设定人设") || text.startsWith("人设")) {
            String persona = text.replaceFirst("^(设定人设|人设)[：:]?\\s*", "");
            if (!persona.isBlank()) {
                ai.setPersona(userId, persona);
                bot.sendText(userId, "✅ 人设已更新：「" + persona + "」");
                System.out.println("[回复] 人设已更新: " + persona);
            } else {
                bot.sendText(userId, "请告诉我想设定的人设，例如：设定人设：你是一只可爱的猫娘");
            }
            return true;
        }

        // "开启语音模式" / "关闭语音模式"
        if ((text.equals("开启语音模式") || text.equals("关闭语音模式")) && tts != null) {
            var sm = ((DeepSeekAiServiceImpl) ai).getSessionManager();
            boolean on = sm.toggleVoiceMode(userId);
            bot.sendText(userId, on ? "✅ 语音模式已开启，所有回复将附带语音。"
                                    : "🔇 语音模式已关闭。");
            return true;
        }

        // "切换音色 xxx"
        if (text.startsWith("切换音色") && tts != null) {
            String voiceName = text.replaceFirst("^切换音色\\s*", "").strip();
            try {
                tts.setVoice(voiceName);
                bot.sendText(userId, "✅ 已切换到音色「" + voiceName + "」。");
            } catch (Exception e) {
                bot.sendText(userId, e.getMessage());
            }
            return true;
        }

        // "查看音色库" / "音色库"
        if ((text.equals("查看音色库") || text.equals("音色库")) && tts != null) {
            bot.sendText(userId, tts.listVoices());
            return true;
        }

        // "新建bot xxx" / "添加机器人 xxx" — 运行时动态新增一个 bot
        if (text.startsWith("新建bot") || text.startsWith("添加机器人")) {
            String name = text.replaceFirst("^(新建bot|添加机器人)\\s*", "").strip();
            if (name.isBlank()) {
                bot.sendText(userId, "请指定名称，例如：新建bot 客服2号");
                return true;
            }
            cluster.addBotDynamic(name);
            bot.sendText(userId, "✅ 正在为「" + name + "」生成登录二维码，请在服务器终端查看并扫码。");
            System.out.println("[回复] 新建bot: " + name);
            return true;
        }

        return false;
    }

    // ---- 工具构建（动态注册 — 根据服务可用性和缓存状态）----

    /**
     * 构建当前可用的 Function Calling 工具列表。
     * 条件工具（图片追问/文档追问）只在有缓存时才注册，避免 AI 幻觉调用。
     */
    /** 构建工具列表 — 委托给 ToolCenter */
    private static void buildTools(
            java.util.List<FunctionDefinition> tools,
            java.util.Map<String, java.util.function.Function<JsonObject, String>> executors,
            ILinkBot bot, AiService ai,
            CalculatorService calc,
            RandomService random, ExpressService express,
            FootballService football, DietService diet,
            WeatherBotService weather, VisionService vision,
            ImageGenService imageGen, NewsService news,
            FinanceService finance, WebReaderService webReader, String userId) {
        toolCenter.buildTools(tools, executors, userId);

        // --- 音乐搜索试听（直接注册，不走 ToolCenter 以保持对 bot 的访问）---
        tools.add(functionDef("play_music",
            "搜索并播放歌曲试听。当用户说「我想听」「放一首」「来一首」「唱一首」「播放」等时调用。",
            Map.of(
                "song", Map.of("type", "string", "description", "歌曲名称，例如：七里香、孤勇者"),
                "artist", Map.of("type", "string", "description", "歌手名称（可选），例如：周杰伦")
            )));
        executors.put("play_music", args -> {
            String song = args.has("song") ? args.get("song").getAsString() : "";
            String artist = args.has("artist") ? args.get("artist").getAsString() : "";
            String result = music.search(song, artist);
            if (result.contains("音频URL:")) {
                String rawUrl = result.substring(result.indexOf("音频URL:") + 7).trim();
                final String audioUrl = rawUrl.substring(0,
                    rawUrl.indexOf("\n") > 0 ? rawUrl.indexOf("\n") : rawUrl.length()).trim();
                IMAGE_EXECUTOR.submit(() -> {
                    try {
                        byte[] data = ((MusicServiceImpl) music).downloadSong(audioUrl);
                        bot.sendFile(userId, data, (song.isBlank() ? "music" : song) + ".mp3", "🎵 " + song);
                    } catch (Exception ignored) {}
                });
            }
            return result;
        });
    }

    // ============================================================
    //  工具中心 — 统一注册所有 FC 工具
    //  新增工具走这里注册，原有 buildTools 中的工具可逐步迁移
    // ============================================================

    /** 向工具中心注册所有 FC 工具。条件工具使用 ToolCondition 实现 per-request 评估。 */
    private static void registerAllTools(
            AiService ai,
            WeatherBotService weather, CalculatorService calc,
            RandomService random, ExpressService express,
            FootballService football, DietService diet,
            ImageGenService imageGen, VisionService vision,
            NewsService news, FinanceService finance,
            WebReaderService webReader, WebSearchService search) {

        BotState bs = botState(ai);

        // ---- 天气 ----
        toolCenter.register(new ToolDefinition("get_weather",
            "查询指定城市的实时天气信息，包括温度、体感温度、湿度、天气状况、风速风向。" +
            "当用户询问天气、气温、会不会下雨、冷不冷、热不热、穿什么衣服等问题时调用此工具。",
            Map.of("city", Map.of("type", "string", "description", "城市名称，例如：北京、上海、东京")),
            args -> {
                String city = args.has("city") ? args.get("city").getAsString() : "";
                return weather != null ? weather.query(city) : "天气服务未配置";
            }));

        // ---- 新闻 ----
        toolCenter.register(new ToolDefinition("get_news",
            "获取最新新闻。当用户询问任何新闻、热点、时事、最新消息时调用此工具。" +
            "即使用户问「你能查新闻吗」「有什么新闻」等关于新闻能力的问题，也调用此工具来展示实际新闻。" +
            "类别可选：综合、国际、科技、财经、体育、文化、健康、教育。",
            Map.of("category", Map.of("type", "string",
                "description", "新闻类别。用户提到具体类别时传入，否则传「综合」。")),
            args -> {
                String category = "综合";
                try { if (args.has("category") && !args.get("category").isJsonNull())
                    category = args.get("category").getAsString(); } catch (Exception ignored) {}
                String result = news.getNews(category, 8);
                bs.putNews(ToolCenter.currentUserId(), news.getLastResults());
                return result;
            }));

        // ---- 新闻详情（条件：新闻服务可用）----
        toolCenter.register(new ToolDefinition("read_news_article",
            "用户想了解某条新闻的详细内容。用户说「第X条」「某标题详细说说」等时调用。" +
            "传入新闻标题中的关键词即可，不要编造序号。工具只返回标题和摘要，没有全文。禁止编造正文。",
            Map.of("query", Map.of("type", "string",
                "description", "新闻标题关键词，例如「女排」。不要传序号。")),
            args -> {
                String query = "";
                try { if (args.has("query") && !args.get("query").isJsonNull())
                    query = args.get("query").getAsString(); } catch (Exception ignored) {}
                var newsItems = bs.getNews(ToolCenter.currentUserId());
                if (newsItems == null) return "请先查询新闻。";
                return news.getArticleDetail(query);
            },
            userId -> news.isAvailable()
        ));

        // ---- 计算器：复利 ----
        toolCenter.register(new ToolDefinition("calculate_compound_interest",
            "计算复利终值。当用户询问复利、投资回报、利滚利等问题时调用。" +
            "需要本金、年利率、年限，可选每年复利次数（默认1）。",
            Map.of(
                "principal", Map.of("type", "number", "description", "本金金额（元）"),
                "annual_rate", Map.of("type", "number", "description", "年利率（百分比，如 5 表示 5%）"),
                "years", Map.of("type", "integer", "description", "投资年限"),
                "times_per_year", Map.of("type", "integer", "description", "每年复利次数，默认 1，按月复利填 12")
            ),
            args -> {
                double p = args.has("principal") ? args.get("principal").getAsDouble() : 0;
                double r = args.has("annual_rate") ? args.get("annual_rate").getAsDouble() : 0;
                int y = args.has("years") ? args.get("years").getAsInt() : 1;
                int t = args.has("times_per_year") ? args.get("times_per_year").getAsInt() : 1;
                return calc.compoundInterest(p, r, y, t);
            }));

        // ---- 计算器：房贷 ----
        toolCenter.register(new ToolDefinition("calculate_mortgage",
            "计算房贷月供（等额本息或等额本金）。当用户询问房贷、月供、贷款还款时调用。" +
            "需要贷款总额、年利率、年限。",
            Map.of(
                "loan_amount", Map.of("type", "number", "description", "贷款总额（元）"),
                "annual_rate", Map.of("type", "number", "description", "年利率（百分比，如 4.9 表示 4.9%）"),
                "years", Map.of("type", "integer", "description", "贷款年限"),
                "method", Map.of("type", "string", "description", "还款方式：equal_interest（等额本息，默认）、equal_principal（等额本金）")
            ),
            args -> {
                double loan = args.has("loan_amount") ? args.get("loan_amount").getAsDouble() : 0;
                double rate = args.has("annual_rate") ? args.get("annual_rate").getAsDouble() : 0;
                int years = args.has("years") ? args.get("years").getAsInt() : 0;
                String method = args.has("method") ? args.get("method").getAsString() : "equal_interest";
                return calc.mortgage(loan, rate, years, method);
            }));

        // ---- 计算器：个税 ----
        toolCenter.register(new ToolDefinition("calculate_tax",
            "计算个人所得税及税后收入（2024年累进税率表）。当用户询问个税、所得税、扣税、税后工资时调用。" +
            "需要税前月薪，可选五险一金金额和专项附加扣除。",
            Map.of(
                "monthly_salary", Map.of("type", "number", "description", "税前月薪（元）"),
                "social_insurance", Map.of("type", "number", "description", "五险一金金额，填0则按10.5%估算"),
                "special_deduction", Map.of("type", "number", "description", "专项附加扣除金额，默认0")
            ),
            args -> {
                double salary = args.has("monthly_salary") ? args.get("monthly_salary").getAsDouble() : 0;
                double insurance = args.has("social_insurance") ? args.get("social_insurance").getAsDouble() : 0;
                double deduction = args.has("special_deduction") ? args.get("special_deduction").getAsDouble() : 0;
                return calc.calculateTax(salary, insurance, deduction);
            }));

        // ---- 计算器：汇率 ----
        toolCenter.register(new ToolDefinition("convert_currency",
            "实时汇率转换。当用户询问汇率、货币换算、兑换时调用。" +
            "需要金额、源货币代码、目标货币代码。",
            Map.of(
                "amount", Map.of("type", "number", "description", "金额"),
                "from_currency", Map.of("type", "string", "description", "源货币代码，如 USD、CNY、EUR、JPY"),
                "to_currency", Map.of("type", "string", "description", "目标货币代码，如 USD、CNY、EUR、JPY")
            ),
            args -> {
                double amount = args.has("amount") ? args.get("amount").getAsDouble() : 0;
                String from = args.has("from_currency") ? args.get("from_currency").getAsString() : "";
                String to = args.has("to_currency") ? args.get("to_currency").getAsString() : "";
                return calc.convertCurrency(amount, from, to);
            }));

        // ---- 日期时间 ----
        toolCenter.register(new ToolDefinition("get_datetime",
            "查询指定城市或时区的当前日期时间。当用户问「现在几点」「当地时间」「东京现在几点」「纽约时间」等问题时调用。",
            Map.of("timezone", Map.of("type", "string", "description",
                "时区或城市名称，例如：Asia/Shanghai、纽约、东京、洛杉矶、伦敦")),
            args -> {
                String tz = args.has("timezone") ? args.get("timezone").getAsString() : "Asia/Shanghai";
                return dateTime.query(tz);
            }));

        // ---- 随机工具 ----
        toolCenter.register(new ToolDefinition("roll_dice",
            "掷骰子。当用户说掷骰、投骰、roll dice、来颗骰子等问题时调用。",
            Map.of("count", Map.of("type", "integer", "description", "骰子个数，默认 1"),
                   "sides", Map.of("type", "integer", "description", "每个骰子的面数，默认 6")),
            args -> random.rollDice(
                args.has("count") ? args.get("count").getAsInt() : 1,
                args.has("sides") ? args.get("sides").getAsInt() : 6)));

        toolCenter.register(new ToolDefinition("random_number",
            "生成指定范围内的随机整数。当用户要随机数、摇号、抽号码时调用。",
            Map.of("min", Map.of("type", "integer", "description", "最小值（含）"),
                   "max", Map.of("type", "integer", "description", "最大值（含）")),
            args -> {
                if (!args.has("min") || !args.has("max")) return "请提供 min 和 max 参数。";
                return random.randomInt(args.get("min").getAsInt(), args.get("max").getAsInt());
            }));

        toolCenter.register(new ToolDefinition("random_choice",
            "从多个选项中随机抽取一个。当用户说帮我选、抽签、随机决定、今晚吃什么等问题时调用。",
            Map.of("options", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "选项列表，例如：[\"火锅\", \"烧烤\", \"寿司\"]")),
            args -> {
                if (!args.has("options") || !args.get("options").isJsonArray())
                    return "请提供 options 数组，例如 [\"A\", \"B\", \"C\"]。";
                var list = new java.util.ArrayList<String>();
                args.get("options").getAsJsonArray().forEach(e -> list.add(e.getAsString()));
                return random.randomChoice(list);
            }));

        toolCenter.register(new ToolDefinition("flip_coin",
            "抛硬币。当用户说抛硬币、正反面、猜正反等问题时调用。",
            Map.of(), args -> random.flipCoin()));

        // ---- 会话管理 ----
        var sm = ((DeepSeekAiServiceImpl) ai).getSessionManager();

        toolCenter.register(new ToolDefinition("create_session",
            "创建一个新的对话会话。用户说「新建对话」「创建对话」「新对话」「开一个新对话」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "新对话的名称，如果用户没有指定则填「默认」")),
            args -> {
                String name = args.has("name") ? args.get("name").getAsString() : "默认";
                sm.createSession(ToolCenter.currentUserId(), name);
                return "已创建并切换到对话「" + name + "」。";
            }));

        toolCenter.register(new ToolDefinition("switch_session",
            "切换到指定的已有对话。用户说「切换到」「切换对话」「回到」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "要切换到的对话名称")),
            args -> {
                String name = args.has("name") ? args.get("name").getAsString() : "";
                sm.switchTo(ToolCenter.currentUserId(), name);
                return "✅ 已切换到对话「" + name + "」。";
            }));

        toolCenter.register(new ToolDefinition("delete_session",
            "删除指定的对话会话。用户说「删掉」「删除对话」「移除」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "要删除的对话名称")),
            args -> {
                String name = args.has("name") ? args.get("name").getAsString() : "";
                return sm.deleteSession(ToolCenter.currentUserId(), name);
            }));

        toolCenter.register(new ToolDefinition("list_sessions",
            "列出当前用户的所有对话会话。用户说「查看所有对话」「对话列表」「列表」「有哪些对话」等时调用。",
            Map.of(), args -> sm.listSessions(ToolCenter.currentUserId())));

        // ---- 图片追问（条件：有缓存图片 + 识图可用）----
        toolCenter.register(new ToolDefinition("ask_about_image",
            "对用户之前发送的图片进行追问或分析。用户说「照片里」「图中」「这张图」「图片里有什么」等时调用。",
            Map.of("question", Map.of("type", "string", "description", "用户对图片的追问内容")),
            args -> {
                String uid = ToolCenter.currentUserId();
                byte[] imgData = uid != null ? bs.getImage(uid) : null;
                if (imgData == null) return "请先发送一张图片。";
                String question = args.has("question") ? args.get("question").getAsString() : "";
                try {
                    return vision.analyze(imgData,
                        question.isBlank() ? "请详细描述这张图片" : question);
                } catch (Exception e) {
                    return "图片分析失败: " + e.getMessage();
                }
            },
            userId -> bs.isImageAvailable(userId) && vision != null
        ));

        // ---- 文档追问（条件：有缓存文档）----
        toolCenter.register(new ToolDefinition("ask_about_document",
            "对用户之前发送的文件/文档内容进行追问。用户说「文档里」「文件中」「刚才的文档」「这份文件」等时调用。",
            Map.of("question", Map.of("type", "string", "description", "用户对文档的追问内容")),
            args -> {
                String uid = ToolCenter.currentUserId();
                BotState.DocSnapshot ds = uid != null ? bs.getDoc(uid) : null;
                if (ds == null) return "请先发送一个文件。";
                String question = args.has("question") ? args.get("question").getAsString() : "";
                return "文件「" + ds.fileName() + "」的内容如下：\n\n" + ds.content()
                    + "\n\n用户追问：" + question + "\n请根据文件内容回答。";
            },
            userId -> bs.isDocAvailable(userId)
        ));

        // ---- 足球数据（条件：服务可用）----
        if (football != null) {
            toolCenter.register(new ToolDefinition("get_premier_league_standings",
                "查询英超积分榜排名。用户说「英超积分榜」「英超排名」「英超战绩」「现在谁第一」等时调用。",
                Map.of("top", Map.of("type", "integer", "description", "返回前几名，填0或5表示前5名，填0表示全部20队")),
                args -> football.getStandings(args.has("top") ? args.get("top").getAsInt() : 0)));

            toolCenter.register(new ToolDefinition("get_premier_league_matches",
                "查询英超最近比赛结果或赛程。用户说「最近英超比赛」「英超战况」「英超结果」「英超赛程」等时调用。",
                Map.of("type", Map.of("type", "string", "description", "recent=最近已结束的比赛, upcoming=即将进行的比赛"),
                       "count", Map.of("type", "integer", "description", "返回场次数，默认5")),
                args -> {
                    String type = args.has("type") ? args.get("type").getAsString() : "recent";
                    int count = args.has("count") ? args.get("count").getAsInt() : 5;
                    return "upcoming".equals(type)
                        ? football.getUpcomingMatches(count) : football.getRecentMatches(count);
                }));

            toolCenter.register(new ToolDefinition("get_premier_league_matchday",
                "查询英超指定轮次的比赛结果。用户说「第X轮」「Matchday X」等时调用。",
                Map.of("matchday", Map.of("type", "string", "description", "轮次，如 Matchday 1、第5轮")),
                args -> football.getMatchdayResults(
                    args.has("matchday") ? args.get("matchday").getAsString() : "")));

            toolCenter.register(new ToolDefinition("search_football_news",
                "搜索懂球帝足球新闻、转会消息。用户说「转会消息」「最新转会」「XX队新闻」「足球新闻」等时调用。",
                Map.of("keyword", Map.of("type", "string", "description", "搜索关键词，如：转会、英超、利物浦、曼联")),
                args -> football.searchNews(
                    args.has("keyword") ? args.get("keyword").getAsString() : "英超转会")));
        }

        // ---- 饮食推荐（条件：服务可用）----
        if (diet != null) {
            toolCenter.register(new ToolDefinition("get_diet_recommendation",
                "根据用户的身高、体重、目标（减脂/增肌）生成个性化饮食推荐方案。" +
                "当用户说「饮食推荐」「吃什么」「减肥怎么吃」「增肌饮食」「减脂餐」「健身饮食」等时调用。" +
                "⚠️ 你无法知道用户今天吃了什么，禁止编造「已吃XX大卡」「剩余XX大卡」等摄入追踪数据。" +
                "只输出工具返回的结果，不要添加进食记录或实时追踪功能。",
                Map.of("heightCm", Map.of("type", "integer", "description", "身高（厘米）"),
                       "weightKg", Map.of("type", "number", "description", "体重（公斤）"),
                       "goal", Map.of("type", "string", "description", "目标：减脂或增肌")),
                args -> diet.getRecommendation(
                    args.has("heightCm") ? args.get("heightCm").getAsInt() : 0,
                    args.has("weightKg") ? args.get("weightKg").getAsDouble() : 0,
                    args.has("goal") ? args.get("goal").getAsString() : "")));
        }

        // ---- 生图（条件：服务可用）----
        if (imageGen != null) {
            toolCenter.register(new ToolDefinition("generate_image",
                "根据文字描述生成一张图片。当用户说「画」「生成」「来一张」「做一张」「帮我画」等时调用。",
                Map.of("prompt", Map.of("type", "string",
                    "description", "图片的详细描述，例如：一只在屋顶看星星的橘猫")),
                args -> {
                    String prompt = args.has("prompt") ? args.get("prompt").getAsString() : "";
                    if (prompt.isBlank()) return "用户没有提供图片描述";
                    final String p = prompt;
                    final String uid = ToolCenter.currentUserId();
                    final ILinkBot replyBot = BotCluster.current();
                    IMAGE_EXECUTOR.submit(() -> {
                        try {
                            byte[] img = imageGen.generate(p);
                            replyBot.sendImage(uid, img, "generated.png", "「" + p + "」");
                            ai.record(uid, "请帮我生成一张图片：" + p, "图片已生成并发送");
                            System.out.println("[生图] 已发送: " + p);
                        } catch (Exception ex) {
                            System.err.println("[生图] ❌ 失败: " + ex.getMessage());
                            replyBot.sendText(uid, "抱歉，图片生成失败：" + ex.getMessage());
                        }
                    });
                    return "图片生成已启动，主题：「" + p + "」。预计 10~30 秒完成。";
                }));
        }

        // ---- 快递查询（条件：服务可用）----
        if (express != null) {
            toolCenter.register(new ToolDefinition("track_express",
                "查询快递物流轨迹。当用户询问快递、物流、包裹、单号到哪里了、查快递等问题时调用。",
                Map.of("tracking_number", Map.of("type", "string", "description", "快递单号"),
                       "company", Map.of("type", "string", "description", "快递公司，可选，如顺丰、圆通、中通"),
                       "phone", Map.of("type", "string", "description", "手机号后四位，查询顺丰快递时必填")),
                args -> {
                    String tn = args.has("tracking_number")
                        ? args.get("tracking_number").getAsString() : "";
                    String company = args.has("company") ? args.get("company").getAsString() : null;
                    String phone = args.has("phone") ? args.get("phone").getAsString() : null;
                    if (tn.isBlank()) return "请提供快递单号。";
                    return express.query(tn, company, phone);
                }));
        }

        // ---- 股票行情 ----
        toolCenter.register(new ToolDefinition("query_stock",
            "查询 A 股股票实时行情。当用户询问股票、股价、股市、股票代码等问题时调用。" +
            "股票代码为6位数字，如600036（招商银行）、000858（五粮液）。",
            Map.of("code", Map.of("type", "string", "description", "股票代码，6位数字，如 600036")),
            args -> {
                String code = args.has("code") ? args.get("code").getAsString() : "";
                return finance.queryStock(code);
            }));

        // ---- 基金净值估值 ----
        toolCenter.register(new ToolDefinition("query_fund",
            "查询基金净值和实时估值。当用户询问基金、净值、估值、理财等问题时调用。" +
            "基金代码为6位数字，如000001（华夏成长）、161725（招商中证白酒）。",
            Map.of("code", Map.of("type", "string", "description", "基金代码，6位数字，如 000001")),
            args -> {
                String code = args.has("code") ? args.get("code").getAsString() : "";
                return finance.queryFund(code);
            }));

        // ---- 加密货币行情 ----
        toolCenter.register(new ToolDefinition("query_crypto",
            "查询加密货币实时价格。当用户询问比特币、BTC、以太坊、ETH、狗狗币、加密货币等问题时调用。" +
            "支持 BTC、ETH、DOGE、SOL、XRP 等主流币种。",
            Map.of("symbol", Map.of("type", "string", "description", "加密货币符号，如 BTC、ETH、DOGE")),
            args -> {
                String symbol = args.has("symbol") ? args.get("symbol").getAsString() : "";
                return finance.queryCrypto(symbol);
            }));

// ---- 网页内容抓取与摘要 ----
        toolCenter.register(new ToolDefinition("read_web_page",
            "读取网页内容并总结要点。当用户发来一篇微信公众号文章或新闻链接，需要提取正文并总结时调用。" +
            "支持微信公众号、新闻网站等常见网页。用户说「读链接」「帮我读这篇文章」「总结一下」等时调用。",
            Map.of("url", Map.of("type", "string", "description", "网页链接，如 https://mp.weixin.qq.com/s/xxx")),
            args -> {
                String url = args.has("url") ? args.get("url").getAsString() : "";
                return webReader.summarize(url, ai, ToolCenter.currentUserId());
            }));

        // ---- 联网搜索（条件：API Key 已配置）----
        if (search != null) {
            toolCenter.register(new ToolDefinition("web_search",
                "联网搜索互联网获取实时信息。" +
                "当用户询问的问题超出已有工具（天气/新闻/足球/股票/计算器/快递/饮食等）的覆盖范围时调用此工具。" +
                "例如：最近发生的新闻事件、名人动态、产品价格、学术知识、百科查询等。" +
                "搜索结果为 Google 实时结果，包含标题、摘要和链接。" +
                "**优先级**：如果能用 get_news、get_weather、query_stock 等专用工具满足需求，优先使用专用工具。",
                Map.of(
                    "query", Map.of("type", "string", "description", "搜索关键词，用中文或英文"),
                    "num", Map.of("type", "integer", "description", "返回结果数，默认5，最多10")
                ),
                args -> {
                    String query = args.has("query") ? args.get("query").getAsString() : "";
                    int num = args.has("num") ? args.get("num").getAsInt() : 5;
                    return search.search(query, num);
                }));
        }
    }

    /** 快捷构建 FunctionDefinition */
    @SuppressWarnings("unchecked")
    private static FunctionDefinition functionDef(String name, String description,
                                                   Map<String, ?> properties) {
        return FunctionDefinition.builder()
                .name(name)
                .description(description)
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from((Object) properties))
                        .build())
                .build();
    }

    // ---- 语音回复辅助 ----

    private static void sendAsVoice(ILinkBot bot, SpeechService tts,
                                     String userId, String reply) {
        try {
            byte[] audio = tts.textToSpeech(cleanForTts(reply));
            // 只发音频文件，不再重复发文字
            bot.sendFile(userId, audio, "reply.wav", "");
            System.out.println("[语音] 已发送");
        } catch (Exception e) {
            System.err.println("[语音] ❌ TTS 失败: " + e.getMessage());
        }
    }

    // ---- 文件消息（提取文本 → AI 总结）----

    private static void handleFile(ILinkBot bot, AiService ai, String userId,
                                    BotMessage msg) {
        byte[] data = msg.fileBytes();
        String fileName = msg.fileName();
        if (data == null || data.length == 0) {
            bot.sendText(userId, "文件为空，无法处理。");
            return;
        }

        // 尝试多种编码读取文本文件
        String content = readTextFile(data);
        if (content == null) {
            // 尝试 PDF 提取
            content = readPdf(data);
        }
        if (content == null) {
            // 尝试 Word 提取
            content = readDocx(data);
        }
        if (content == null) {
            // 尝试 Excel 提取
            content = readXlsx(data);
        }

        if (content == null || content.isBlank()) {
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
            bot.sendText(userId, "「" + fileName + "」无法解析。\n目前支持：TXT、代码文件、CSV、日志等文本格式。");
            return;
        }

        int maxLen = 8000;
        String truncated = content.length() > maxLen ? content.substring(0, maxLen) + "\n...(后续内容已截断)" : content;

        System.out.println("[Bot] 📄 文件(" + content.length() + "字): " + truncated.substring(0, Math.min(80, truncated.length())));

        String reply = ai.chat(userId,
            "请总结以下文件「" + fileName + "」的内容，用简洁中文分点列出关键信息：\n\n" + truncated);
        System.out.println("[回复] " + reply);
        bot.sendTextWithTyping(userId, "📄 「" + fileName + "」总结：\n" + reply, 500L);

        // 缓存文档内容，支持追问
        botState(ai).putDoc(userId, truncated, fileName);
    }

    /** 统计字符串中 Unicode 替换字符(�)的数量 */
    private static int countReplacementChars(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '�') count++;
        }
        return count;
    }

    /** 尝试多种编码读文本文件 */
    private static String readTextFile(byte[] data) {
        for (String enc : new String[]{"UTF-8", "GBK", "GB2312"}) {
            try {
                String s = new String(data, java.nio.charset.Charset.forName(enc));
                if (countReplacementChars(s) < s.length() * 0.05) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 提取 PDF 文本 */
    private static String readPdf(byte[] data) {
        try {
            var doc = org.apache.pdfbox.Loader.loadPDF(data);
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setEndPage(20); // 最多读 20 页
            String text = stripper.getText(doc);
            doc.close();
            return text.strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取 Word 文本 */
    private static String readDocx(byte[] data) {
        try {
            var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(data));
            var sb = new StringBuilder();
            for (var p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
                if (sb.length() > 15000) break;
            }
            doc.close();
            return sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取 Excel 文本 */
    private static String readXlsx(byte[] data) {
        try {
            var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(data));
            var sb = new StringBuilder();
            var sheet = wb.getSheetAt(0);
            for (var row : sheet) {
                for (var cell : row) {
                    String v = cell.toString();
                    if (!v.isEmpty()) sb.append(v).append("\t");
                }
                sb.append("\n");
                if (sb.length() > 15000) break;
            }
            wb.close();
            return sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 图片识别 ----

    private static void handleImage(ILinkBot bot, AiService ai, VisionService vision,
                                      BotMessage msg) {
        if (vision == null) {
            bot.sendText(msg.userId(), "图片识别服务未启用，请联系管理员设置 ark.vision.api.key。");
            return;
        }
        String prompt = msg.text().isEmpty() ? null : msg.text();
        System.out.println("[Bot] 👁 检测到图片消息，开始识别...");
        String result = vision.analyze(msg.imageBytes(), prompt);
        System.out.println("[回复] " + result);
        // 缓存图片，支持后续追问
        botState(ai).putImage(msg.userId(), msg.imageBytes());

        // 记入对话历史，让 AI 知道刚才识图的内容
        ai.record(msg.userId(), "[发送了一张图片" + (prompt != null ? "，询问：" + prompt : "") + "]",
                  result);

        // 追加追问提示
        result += "\n\n💡 你可以继续追问，比如「照片里有什么动物」「这是什么地方」";
        bot.sendTextWithTyping(msg.userId(), result, 500L);
    }

    // ---- 语音消息（提取文字 → 统一路由 → 强制语音回复）----

    private static void handleVoice(ILinkBot bot, AiService ai, SpeechService tts,
                                     CalculatorService calc,
                                     RandomService random, ExpressService express,
                                     FootballService football, DietService diet,
                                     WeatherBotService weather, VisionService vision,
                                     ImageGenService imageGen, NewsService news,
                                     FinanceService finance,
                                     WebReaderService webReader,
                                     BotMessage msg) {
        String userId = msg.userId();
        String text = msg.voiceText();
        if (text == null || text.isBlank()) {
            bot.sendText(userId, "收到了你的语音，但无法识别内容。请尝试用文字发送～");
            return;
        }

        System.out.println("[Bot] 🎤 语音识别: " + text);
        // 统一走文字路由，forceVoice=true 确保回复一定带语音
        processTextMessage(bot, ai, tts, calc, random, express, football, diet, weather, vision, imageGen, news, finance, webReader, userId, text, true);
    }

    // ---- 工具方法 ----

    /** 从 AiService 中获取 BotState 缓存 */
    private static BotState botState(AiService ai) {
        return ((DeepSeekAiServiceImpl) ai).getBotState();
    }

    /** 检查文本是否包含任意一个关键词 */
    private static boolean containsKeyword(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isVoiceMode(AiService ai, String userId) {
        return ((DeepSeekAiServiceImpl) ai).getSessionManager().isVoiceMode(userId);
    }

    /** 清理 TTS 文字：去掉不应朗读的标记，保留语气符号 */
    private static String cleanForTts(String text) {
        return text
            // 去掉所有括号内容：（笑）（无奈）（用娇软的声音...）—— TTS 不应朗读
            .replaceAll("[（(][^）)]*[）)]", "")
            // 去掉方括号 emoji 代码
            .replaceAll("\\[.*?\\]", "")
            // 去掉 Markdown 标记
            .replaceAll("\\*\\*|__|\\*", "")
            .replaceAll(" {2,}", " ")
            .strip();
        // 注意：保留 ～ 和 ~ ，它们影响 TTS 的语调和停顿，让语音更自然
    }
}
