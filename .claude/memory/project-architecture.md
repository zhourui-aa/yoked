---
name: project-architecture
description: 项目整体架构、分层、接口与实现对应关系
metadata:
  type: project
---

## 项目：微信 AI 聊天机器人 (yoked)

**位置**：`D:\youkedaSummer`（已迁移，原 `D:\ykdProjects\wechatTest1`）

### 分层架构
```
BotApp.java (入口+路由) → service/ (接口) → impl/ (实现) → 外部 API
ILinkBot.java (微信 SDK 门面) → BotMessage → handler → 路由
```

### 关键组件
- **主程序**：`BotApp.java` — 静态 main 方法，组装所有服务，注册消息处理器
- **SDK 门面**：`ILinkBot.java` — 封装 wechat-ilink-sdk，长轮询 getUpdates()
- **消息路由**（BotApp handler）：
  1. 语音消息 → handleVoice()
  2. 图片消息 → handleImage()
  3. 文件消息 → handleFile()
  4. 文字消息 → processTextMessage()

### 接口 ↔ 实现
- `AiService` → `DeepSeekAiServiceImpl`（DeepSeek v4-pro，OpenAI SDK 兼容）
- `ImageGenService` → `SeedreamImageServiceImpl`（火山引擎 Seedream 5.0）
- `VisionService` → `DoubaoVisionServiceImpl`（火山引擎 Doubao Vision）
- `SpeechService` → `QwenTtsSpeechServiceImpl`（阿里云 qwen3-tts-flash）
- `WeatherBotService`（具体类，不是接口—包装 com.weather 子模块）
- `NewsService` → `RssNewsServiceImpl`（零 API Key，RSS 解析）

### 线程模型
- `ilink-poller`：长轮询线程，getUpdates() 阻塞等消息
- `msg-handler`：单线程处理消息（ILinkBot.handlerExecutor）
- `image-gen`：异步生图线程池（BotApp.IMAGE_EXECUTOR）
- `main`：Thread.currentThread().join() 阻塞

**Why:** 理解项目结构是任何操作的基础
**How to apply:** 加新功能时在 service/ 定义接口 → impl/ 写实现 → BotApp 中注册
