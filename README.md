# 微信 AI 聊天机器人

基于 wechat-ilink-sdk 的多模态 AI 微信机器人，支持文字对话、图片生成、图片识别、语音合成、天气查询、文件总结等功能。

## 功能一览

| 功能 | 能力 | 服务商 |
|------|------|--------|
| 💬 文字对话 | 多轮对话 + 多会话管理 | DeepSeek |
| 🎨 图片生成 | 说"画一只猫"即可 AI 生图 | 火山引擎 Seedream |
| 👁 图片识别 | 发图秒描述，支持连续追问 | 豆包 Vision |
| 🎤 语音回复 | 文字自动转语音发送 | 阿里云 qwen3-tts-flash |
| 🌤 天气查询 | 问"北京天气"自动查 | 和风天气 |
| 📄 文件总结 | 发 TXT/PDF/Word/Excel 自动总结 | DeepSeek |
| 🔄 多会话 | 一个用户多个独立对话 | 内置 |

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.6+
- 一个微信小号（用于机器人登录）

### 2. 获取 API Key

你需要去以下平台注册并获取 API Key：

| 配置项 | 必需 | 说明 | 获取地址 |
|--------|------|------|----------|
| DeepSeek API Key | ✅ 必需 | 文字对话核心 | https://platform.deepseek.com/api_keys |
| 和风天气 API Key | ⭕ 可选 | 天气查询 | https://console.qweather.com/ |
| 火山引擎 Ark API Key | ⭕ 可选 | 图片生成 | https://console.volcengine.com/ark/region:ark+cn-beijing/apikey |
| 火山引擎 Vision API Key | ⭕ 可选 | 图片识别 | 同上，需开通豆包 Vision 模型 |
| 阿里云 DashScope API Key | ⭕ 可选 | 语音合成 | https://dashscope.console.aliyun.com/apiKey |

> **没填的可选功能会自动禁用**，不影响核心聊天功能。

### 3. 配置 API Key（三选一）

#### 方式 A：编辑 config.properties（推荐）

打开项目根目录的 `config.properties`，填入你的 Key：

```properties
# 必填 — DeepSeek AI 对话
deepseek.api.key=sk-你的deepseek-key

# 可选 — 和风天气
qweather.api.key=你的和风天气key
qweather.api.host=

# 可选 — 火山引擎 生图
ark.api.key=你的ark-key

# 可选 — 火山引擎 识图
ark.vision.api.key=你的vision-key
```

> ⚠️ `config.properties` 包含敏感信息，**不要提交到公开仓库**。已在 `.gitignore` 中忽略。

#### 方式 B：设置环境变量

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-你的key"
$env:QWEATHER_API_KEY="你的key"
$env:ARK_API_KEY="你的key"
$env:ARK_VISION_API_KEY="你的key"
$env:DASHSCOPE_API_KEY="你的key"

# Linux / macOS
export DEEPSEEK_API_KEY="sk-你的key"
export QWEATHER_API_KEY="你的key"
export ARK_API_KEY="你的key"
export ARK_VISION_API_KEY="你的key"
export DASHSCOPE_API_KEY="你的key"
```

#### 方式 C：启动时用 -D 参数

```bash
mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp" \
  -Ddeepseek.api.key="sk-你的key" \
  -Dqweather.api.key="你的key"
```

> 优先级：`-D 参数 > 环境变量 > config.properties`

### 4. 用 Claude / Cursor 帮你配置

把这段提示词发给 Claude：

```
帮我配置这个微信机器人的 API Key：

1. 打开项目根目录的 config.properties
2. deepseek.api.key 填上我的 DeepSeek Key
3. 如果有其他 Key 也可以填上（和风天气、火山引擎、阿里云 DashScope）
4. 注意：config.properties 不要提交到 git

我的 DeepSeek Key：sk-你的key
```

### 5. 运行

```bash
mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp"
```

终端会打印微信登录二维码，用微信小号扫码即可。扫码成功后机器人上线。

### 6. 使用

向机器人发送消息即可对话。支持的命令：

| 命令 | 效果 |
|------|------|
| `帮助` | 查看所有功能 |
| `北京天气` | 查天气 |
| `画一只猫` | AI 生图 |
| `发语音` | 本次回复带语音 |
| `开启语音模式` | 之后所有回复带语音 |
| `设定人设：你是一只猫娘` | 改 AI 人设 |
| `新建对话 xxx` | 创建新会话 |
| `切换到 xxx` | 切换会话 |
| `查看所有对话` | 列表 |
| `删掉 xxx` | 删除会话 |
| `切换音色 xxx` | 换 TTS 音色 |
| `查看音色库` | 查看 14 种音色 |

发送图片、PDF、Word、Excel 等文件也会自动识别/总结。

## 项目结构

```
src/main/java/org/example/bot/
├── BotApp.java                     # 主程序入口 + 消息路由
├── ilink/ILinkBot.java             # 微信 SDK 门面
├── service/                        # 服务接口（可插拔）
│   ├── AiService.java              #   AI 对话接口
│   ├── ImageGenService.java        #   生图接口
│   ├── VisionService.java          #   识图接口
│   └── SpeechService.java          #   语音接口
├── impl/                           # 服务实现
│   ├── DeepSeekAiServiceImpl.java  #   DeepSeek 对话 + Function Calling
│   ├── SeedreamImageServiceImpl.java # 火山引擎生图
│   ├── DoubaoVisionServiceImpl.java  # 豆包识图
│   ├── QwenTtsSpeechServiceImpl.java # 阿里云 TTS
│   ├── SessionManager.java         #   多会话管理
│   └── Session.java                #   会话数据结构
├── model/                          # 数据模型
└── util/ConfigUtil.java           # 配置读取工具
```

## 技术栈

- **微信 SDK**: wechat-ilink-sdk 2.3.3
- **AI 对话**: DeepSeek v4-pro（OpenAI SDK 兼容）
- **图片生成**: 火山引擎 Seedream 5.0
- **图片识别**: 火山引擎 Doubao Vision
- **语音合成**: 阿里云 qwen3-tts-flash（DashScope MultiModalConversation API）
- **天气数据**: 和风天气 API
- **文件解析**: Apache PDFBox 3.x + Apache POI 5.x
- **意图路由**: OpenAI Function Calling（AI 自主选择工具）
