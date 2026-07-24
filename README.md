# 微信 AI 聊天机器人

基于 wechat-ilink-sdk 的多模态 AI 微信机器人，支持文字对话、图片生成、图片识别、语音合成、天气查询、新闻获取、足球数据、饮食推荐、金融计算、快递查询、随机工具等功能。

## 功能一览

| 功能 | 能力 | 服务商 |
|------|------|--------|
| 💬 文字对话 | 多轮对话 + 多会话管理 + Function Calling | DeepSeek |
| 🎨 图片生成 | 说"画一只猫"即可 AI 生图 | 火山引擎 Seedream |
| 👁 图片识别 | 发图秒描述，支持连续追问 | 豆包 Vision |
| 🎤 语音回复 | 文字自动转语音发送 | 阿里云 qwen3-tts-flash |
| 🌤 天气查询 | 问"北京天气"自动查 | 和风天气 |
| 🕐 日期时间 | "东京现在几点""纽约时间" | Kiprio Timezone API |
| 📰 新闻获取 | 8 类别新闻 + 详情追问 | RSS（中国新闻网/IT之家） |
| ⚽ 足球数据 | 英超积分榜/比赛/赛程/转会新闻 | openfootball + 懂球帝 |
| 🥗 饮食推荐 | 减脂/增肌个性化饮食方案 | 内置计算（Mifflin-St Jeor） |
| 🧮 金融计算 | 复利/房贷/个税/实时汇率 | exchangerate-api.com |
| 📦 快递查询 | 单号自动识别物流轨迹 | 快递鸟 API |
| 🎲 随机工具 | 掷骰子/随机数/抽签/抛硬币 | 内置 |
| 📄 文件总结 | 发 TXT/PDF/Word/Excel 自动总结 | DeepSeek |
| 🔄 多会话 | 一个用户多个独立对话 | 内置 |
| 🤖 多 Bot | 多个微信号同时在线，运行时动态新增 | SDK 多实例 |

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.6+
- 一个微信小号（用于机器人登录）

### 2. 获取 API Key

| 配置项 | 必需 | 说明 | 获取地址 |
|--------|------|------|----------|
| deepseek.api.key | ✅ 必需 | 文字对话核心 | https://platform.deepseek.com/api_keys |
| qweather.api.key | ⭕ 可选 | 天气查询 | https://console.qweather.com/ |
| ark.api.key | ⭕ 可选 | 图片生成 | https://console.volcengine.com/ark/region:ark+cn-beijing/apikey |
| ark.vision.api.key | ⭕ 可选 | 图片识别 | 同上，需开通豆包 Vision 模型 |
| dashscope.api.key | ⭕ 可选 | 语音合成 | https://dashscope.console.aliyun.com/apiKey |
| datetime.api.key | ⭕ 可选 | 日期时间（500次/天免费） | https://kiprio.com/signup |
| kdniao.ebusiness.id | ⭕ 可选 | 快递查询（商户ID） | https://www.kdniao.com/ |
| kdniao.app.key | ⭕ 可选 | 快递查询（API Key） | 同上 |

> **没填的可选功能会自动禁用**，不影响核心聊天功能。

### 3. 配置 API Key

编辑项目根目录的 `config.properties`：

```properties
# 必填 — DeepSeek AI 对话
deepseek.api.key=sk-你的deepseek-key

# 可选 — 和风天气
qweather.api.key=你的和风天气key

# 可选 — 火山引擎 生图
ark.api.key=你的ark-key

# 可选 — 火山引擎 识图
ark.vision.api.key=你的vision-key

# 可选 — 阿里云 DashScope 语音合成
dashscope.api.key=你的dashscope-key

# 可选 — 日期时间（免费注册 https://kiprio.com/signup）
datetime.api.key=你的kiprio-key

# 可选 — 快递查询（免费注册 https://www.kdniao.com/）
kdniao.ebusiness.id=你的商户ID
kdniao.app.key=你的app-key
```

> ⚠️ `config.properties` 包含敏感信息，**不要提交到公开仓库**。已在 `.gitignore` 中忽略。

也支持环境变量（`DEEPSEEK_API_KEY` 等）或 `-D` 启动参数。优先级：`-D 参数 > 环境变量 > config.properties`。

### 4. 运行

```bash
# 单 bot（默认）
mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp"

# 多 bot（启动时 3 个微信号同时扫码）
mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp" -Dbots=3
```

终端会打印微信登录二维码，用微信小号扫码即可。

### 5. 使用

向机器人发送消息即可对话。支持的命令：

| 命令 | 效果 |
|------|------|
| `帮助` | 查看所有功能 |
| `北京天气` | 查天气 |
| `画一只猫` | AI 生图 |
| `最新科技新闻` | 获取新闻 |
| `英超积分榜` | 足球排名 |
| `减脂怎么吃` | 饮食推荐 |
| `复利计算 本金10万 年利率5% 投资10年` | 金融计算 |
| `查快递 YT1234567890` | 快递跟踪 |
| `掷骰子` / `抛硬币` | 随机工具 |
| `东京现在几点` | 日期时间 |
| `发语音` | 本次回复带语音 |
| `开启语音模式` | 之后所有回复带语音 |
| `设定人设：你是一只猫娘` | 改 AI 人设 |
| `新建对话 xxx` | 创建新会话 |
| `切换到 xxx` | 切换会话 |
| `查看所有对话` | 列表 |
| `删掉 xxx` | 删除会话 |
| `切换音色 xxx` | 换 TTS 音色（14 种可选） |
| `查看音色库` | 查看可用音色 |
| `新建bot xxx` | 运行时动态新增一个微信号（终端打印新二维码） |

发送图片、PDF、Word、Excel 等文件也会自动识别/总结。

## 项目结构

```
src/main/java/org/example/bot/
├── BotApp.java                          # 主程序入口 + 消息路由 + 工具注册
├── Diagnostic.java                      # 诊断工具
├── ilink/                               # 微信 SDK 封装
│   ├── ILinkBot.java                    #   微信 SDK 门面（登录/收/发）
│   ├── BotMessage.java                  #   消息载体（文字/图片/语音/文件）
│   └── BotCluster.java                  #   Bot 集群（多微信号管理 + 动态新增）
├── service/                             # 服务接口（可插拔）
│   ├── AiService.java                   #   AI 对话接口
│   ├── ImageGenService.java             #   生图接口
│   ├── VisionService.java               #   识图接口
│   ├── SpeechService.java               #   语音接口
│   ├── WeatherBotService.java           #   天气接口
│   ├── NewsService.java                 #   新闻接口
│   ├── FootballService.java             #   足球接口
│   ├── DietService.java                 #   饮食推荐接口
│   ├── DateTimeService.java             #   日期时间接口
│   ├── CalculatorService.java           #   金融计算接口
│   ├── ExpressService.java              #   快递查询接口
│   └── RandomService.java               #   随机工具接口
├── impl/                                # 服务实现
│   ├── DeepSeekAiServiceImpl.java       #   DeepSeek 对话 + Function Calling
│   ├── BotState.java                    #   线程安全缓存（图片/文档/新闻）
│   ├── SeedreamImageServiceImpl.java    #   火山引擎生图
│   ├── DoubaoVisionServiceImpl.java     #   豆包识图
│   ├── QwenTtsSpeechServiceImpl.java    #   阿里云 TTS
│   ├── RssNewsServiceImpl.java          #   RSS 新闻聚合
│   ├── FootballServiceImpl.java         #   英超足球数据
│   ├── DietServiceImpl.java             #   饮食推荐计算
│   ├── DateTimeServiceImpl.java         #   Kiprio 时区查询
│   ├── CalculatorServiceImpl.java       #   金融计算
│   ├── ExpressServiceImpl.java          #   快递鸟物流查询
│   ├── RandomServiceImpl.java           #   随机工具
│   ├── SessionManager.java              #   多会话管理
│   └── Session.java                     #   会话数据结构
├── tools/                               # 工具中心（FC 工具注册与管理）
│   ├── ToolCenter.java                  #   注册中心
│   ├── ToolDefinition.java              #   工具定义数据类
│   ├── ToolCondition.java               #   条件判断接口
│   └── ToolContributor.java             #   服务贡献工具接口
└── util/
    └── ConfigUtil.java                  # 配置读取工具
```

## 技术栈

- **微信 SDK**: wechat-ilink-sdk 2.3.3
- **AI 对话**: DeepSeek v4-pro（OpenAI SDK 兼容）
- **图片生成**: 火山引擎 Seedream 5.0
- **图片识别**: 火山引擎 Doubao Vision
- **语音合成**: 阿里云 qwen3-tts-flash（DashScope SDK）
- **天气数据**: 和风天气 API
- **新闻源**: 中国新闻网 / IT之家（RSS，无需 API Key）
- **足球数据**: openfootball（GitHub）+ 懂球帝搜索
- **快递查询**: 快递鸟 API（14 家快递公司）
- **汇率数据**: exchangerate-api.com（免费无需注册）
- **日期时间**: Kiprio Timezone API（500次/天免费）
- **文件解析**: Apache PDFBox 3.x + Apache POI 5.x
- **意图路由**: OpenAI Function Calling（AI 自主选择工具）
