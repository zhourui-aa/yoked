# 微信 AI 聊天机器人

基于 wechat-ilink-sdk 的多模态 AI 微信机器人，支持文字对话、图片生成、图片识别、语音合成、天气查询、原生联网搜索、新闻获取、足球数据、饮食推荐、金融行情、金融计算、快递查询、音乐搜索、成语接龙、垃圾分类、模拟人生桌游等功能。

## 功能一览

| 功能 | 能力 | 服务商 |
|------|------|--------|
| 💬 文字对话 | 多轮对话 + 多会话管理 + Function Calling | DeepSeek v4-flash |
| 🌐 原生联网搜索 | 实时信息查询，自动联网并带引用 | DeepSeek 原生联网 |
| 📖 网页阅读 | 发送链接自动抓取正文并 AI 总结 | 内置 |
| 🎨 图片生成 | 说"画一只猫"即可 AI 生图 | 火山引擎 Seedream |
| 👁 图片识别 | 发图秒描述，支持连续追问 | 豆包 Vision |
| 🎤 语音回复 | 文字自动转语音发送 | 阿里云 qwen3-tts-flash |
| 🌤 天气查询 | 问"北京天气"自动查 | 和风天气 |
| 🕐 日期时间 | "东京现在几点""纽约时间" | Kiprio Timezone API |
| 📰 新闻获取 | 8 类别新闻 + 详情追问 | RSS（中国新闻网/IT之家） |
| ⚽ 足球数据 | 英超积分榜/比赛/赛程/转会新闻 | openfootball + 懂球帝 |
| 💹 金融行情 | A股/基金净值/加密货币实时价格 | 新浪/天天基金/Binance |
| 🥗 饮食推荐 | 减脂/增肌个性化饮食方案 | 内置计算（Mifflin-St Jeor） |
| 🧮 金融计算 | 复利/房贷/个税/实时汇率 | exchangerate-api.com |
| 📦 快递查询 | 单号自动识别物流轨迹 | 快递鸟 API |
| 🎵 音乐搜索 | 搜歌试听，自动发送音频 | NeteaseCloudMusicApi |
| 🎯 成语接龙 | 机器人陪玩，内置 300+ 成语词典 | 内置 |
| 🗑 垃圾分类 | 150+ 物品四分类查询 | 内置 |
| 🎲 随机工具 | 掷骰子/随机数/抽签/抛硬币 | 内置 |
| 📄 文件总结 | 发 TXT/PDF/Word/Excel 自动总结 | DeepSeek |
| 🎮 模拟人生 | 单人人生模拟：天赋系统 + 事件库 + AI 叙事 | 内置 |
| 🎭 桌游大厅 | 剧本杀/狼人杀/谁是卧底/海龟汤/密码破译 | 内置 + AI 主持 |
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
| deepseek.api.key | ✅ 必需 | 文字对话 + 原生联网搜索 | https://platform.deepseek.com/api_keys |
| qweather.api.key | ⭕ 可选 | 天气查询 | https://console.qweather.com/ |
| ark.api.key | ⭕ 可选 | 图片生成 | https://console.volcengine.com/ark/region:ark+cn-beijing/apikey |
| ark.vision.api.key | ⭕ 可选 | 图片识别 | 同上，需开通豆包 Vision 模型 |
| dashscope.api.key | ⭕ 可选 | 语音合成 | https://dashscope.console.aliyun.com/apiKey |
| datetime.api.key | ⭕ 可选 | 日期时间（500次/天免费） | https://kiprio.com/signup |
| kdniao.ebusiness.id | ⭕ 可选 | 快递查询（商户ID） | https://www.kdniao.com/ |
| kdniao.app.key | ⭕ 可选 | 快递查询（API Key） | 同上 |

> **没填的可选功能会自动禁用**，不影响核心聊天功能。
> **联网搜索无需额外 Key**：DeepSeek V4 Flash 原生支持，配好 `deepseek.api.key` 即可用。

### 3. 配置 API Key

编辑项目根目录的 `config.properties`：

```properties
# 必填 — DeepSeek AI 对话（含原生联网搜索）
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
| `2026年有什么大事件` | 原生联网搜索 |
| `英超积分榜` | 足球排名 |
| `减脂怎么吃` | 饮食推荐 |
| `复利计算 本金10万 年利率5% 投资10年` | 金融计算 |
| `查快递 YT1234567890` | 快递跟踪 |
| `掷骰子` / `抛硬币` | 随机工具 |
| `东京现在几点` | 日期时间 |
| `模拟人生` | 单人人生模拟游戏 |
| `桌游模式` | 桌游大厅（剧本杀/狼人杀等） |
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

## 桌游玩法

说「桌游模式」进入大厅，支持：

| 游戏 | 人数 | 说明 |
|------|------|------|
| 🎭 剧本杀 | 4-9人 | AI 主持人当场生成案件，推理找出真凶 |
| 🐺 狼人杀 | 6-12人 | 预女猎白体系，屠边规则 |
| 🕵 谁是卧底 | 4-12人 | 相似词推理，AI 主持 |
| 🐢 海龟汤 | 多人 | AI 出汤面，玩家提问还原真相 |
| 🔐 密码破译 | 1人 | 猜 4 位不重复数字密码 |

## 项目结构

```
src/main/java/
├── org/example/bot/                 # 主程序
│   ├── BotApp.java                  # 主入口 + 消息路由 + 工具注册
│   ├── Diagnostic.java              # 诊断工具
│   ├── ilink/                       # 微信 SDK 封装（多 bot 管理）
│   ├── service/                     # 服务接口（可插拔）
│   ├── impl/                        # 服务实现
│   ├── tools/                       # 工具中心（FC 工具注册）
│   ├── skill/                       # Skill 系统
│   ├── rag/                         # RAG 检索
│   └── util/                        # 配置读取
├── game/                            # 桌游引擎
│   ├── GameRegistry.java            # 游戏注册中心
│   ├── GameCommand.java             # 游戏命令路由
│   └── impl/                        # 各游戏引擎
│       ├── LifeSimEngine.java       #   模拟人生
│       ├── MurderMysteryEngine.java #   剧本杀
│       ├── WerewolfEngine.java      #   狼人杀
│       ├── UndercoverEngine.java    #   谁是卧底
│       ├── TurtleSoupEngine.java    #   海龟汤
│       └── CodeBreakerEngine.java   #   密码破译
└── resources/game/impl/             # 游戏数据
    ├── events.json                  #   模拟人生事件库
    └── talents.json                 #   模拟人生天赋库
```

## 技术栈

- **微信 SDK**: wechat-ilink-sdk 2.3.3
- **AI 对话**: DeepSeek v4-flash（OpenAI SDK 兼容 + 原生联网）
- **图片生成**: 火山引擎 Seedream 5.0
- **图片识别**: 火山引擎 Doubao Vision
- **语音合成**: 阿里云 qwen3-tts-flash（DashScope SDK）
- **天气数据**: 和风天气 API
- **联网搜索**: DeepSeek V4 Flash 原生联网（无需额外 Key）
- **网页读取**: JDK HttpClient + HTML 正文提取 + AI 摘要
- **新闻源**: 中国新闻网 / IT之家（RSS，无需 API Key）
- **足球数据**: openfootball（GitHub）+ 懂球帝搜索
- **金融行情**: 新浪财经 / 天天基金 / Binance（免费无需 Key）
- **快递查询**: 快递鸟 API（14 家快递公司）
- **音乐搜索**: NeteaseCloudMusicApi（免费无需 Key）
- **汇率数据**: exchangerate-api.com（免费无需注册）
- **日期时间**: Kiprio Timezone API（500次/天免费）
- **文件解析**: Apache PDFBox 3.x + Apache POI 5.x
- **意图路由**: OpenAI Function Calling（AI 自主选择工具）
- **数据驱动**: 模拟人生事件/天赋均为 JSON 数据驱动
