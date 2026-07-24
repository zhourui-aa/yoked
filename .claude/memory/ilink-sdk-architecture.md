---
name: ilink-sdk-architecture
description: wechat-ilink-sdk 2.3.3 完整源码分析 — 架构、数据流、关键设计
metadata:
  type: reference
---

## 概述
`io.github.lith0924:wechat-ilink-sdk:2.3.3` — 微信 iLink 机器人 Java SDK。
通过二维码扫码登录微信，然后以长轮询方式收发消息。所有通信走微信 iLink API（`ilinkai.weixin.qq.com`），媒体文件走微信 CDN（`novac2c.cdn.weixin.qq.com`）。

## 包结构（65 个 Java 文件）

```
com.github.wechat.ilink.sdk/
├── ILinkClient          — 核心门面，所有 API 的入口
├── ILinkClientBuilder   — Builder 模式
├── core/config/         — ILinkConfig（超时/重试/心跳/线程池/…）
├── core/context/        — 会话上下文管理（context_token 是收发消息的关键）
├── core/crypto/         — AES-ECB PKCS7 加解密（媒体文件）
├── core/exception/      — 7 种异常（SessionExpired 最重要）
├── core/executor/       — IO 线程池 + 定时调度器
├── core/http/           — OkHttp 封装 + 业务 API 客户端
├── core/lifecycle/      — 心跳服务
├── core/listener/       — 4 种事件监听器（CopyOnWriteArrayList）
├── core/login/          — 登录上下文 + 状态机
├── core/model/          — 15 个数据模型
├── core/retry/          — 指数退避 + jitter 重试策略
├── core/serializer/     — JSON 序列化
├── core/state/          — 连接状态管理（7 种状态）
├── core/utils/          — Hash/Hex/Random 工具
└── service/             — 5 个服务（Login/Update/Message/Media/Typing）
```

## 核心数据流

### 1. 登录流程
```
GET /ilink/bot/get_bot_qrcode?bot_type=3
  → qrcode + qrcode_img_content（终端二维码）
  → 轮询 GET /ilink/bot/get_qrcode_status?qrcode=xxx
    → waiting → scanned → confirmed / expired / timeout
  → LoginContext(botToken, userId, botId, baseUrl)
```

### 2. 消息接收（长轮询）
```
POST /ilink/bot/getupdates
  Body: { get_updates_buf: cursor, base_info: {...} }
  → GetUpdatesResponse { msgs: [...], get_updates_buf: newCursor }
  → 自动从消息中提取 context_token 更新 ConversationContext
  → cursor 持久化（GetUpdatesCursorStore），下次用新 cursor 继续
```

⚠️ **pollLock** — 整个 ILinkClient 只有一个 `pollLock`（`synchronized`），心跳和用户调用的 `getUpdates()` 共享同一把锁，避免游标竞争（[GitHub issue #5](https://github.com/lith0924/wechat-ilink-sdk-java/issues/5)）。

### 3. 消息发送
```
POST /ilink/bot/sendmessage
  Body: {
    msg: {
      to_user_id, client_id, context_token,  // ← context_token 必须是最新的！
      message_type: 2, message_state: 2,
      item_list: [MessageItem]
    },
    base_info: {...}
  }
```

消息类型 (MessageItem.type):
| type | 说明 | 对应的 item 字段 |
|------|------|-----------------|
| 1 | 文本 | text_item |
| 2 | 图片 | image_item (CDNMedia + aeskey) |
| 3 | 语音 | voice_item (SILK 编码，6) |
| 4 | 文件 | file_item |
| 5 | 视频 | video_item |

### 4. 媒体上传流程（关键！）
```
客户端:
  1. 生成随机 AES key (16 bytes hex)
  2. AES-ECB PKCS7 加密媒体数据
  3. POST /ilink/bot/getuploadurl → 获取 upload_param + thumb_upload_param
  4. PUT 加密数据到 CDN: https://novac2c.cdn.weixin.qq.com/c2c/upload?... 
  5. 从响应头获取 x-encrypted-param
  6. 构造 CDNMedia { encrypt_query_param, aes_key(base64), encrypt_type:1 }
  7. 将 CDNMedia 放入 MessageItem 发送
```

### 5. 媒体下载
```
CDNMedia.encrypt_query_param + aes_key
  → GET https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=...
  → AES-ECB PKCS7 解密（key 可能是 base64 → hex 解码或直接 hex 解码）
```

### 6. 会话上下文（Context Token）
这是收发消息的**核心机制**：
- `ContextPoolManager`: `ConcurrentHashMap<ContextKey(botId,userId), ConversationContext>`
- 收到消息时自动从 `WeixinMessage.context_token` 更新
- 发送消息时**必须**携带最新的 context_token，否则 API 返回错误
- 支持 `snapshot()` / `restore()` 用于进程重启恢复
- 无 context_token 时发消息会抛 `ILinkException("missing latest context token")`

## 关键配置 (ILinkConfig) 及其默认值

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| connectTimeoutMs | 35000 | OkHttp 连接超时 |
| readTimeoutMs | 35000 | OkHttp 读取超时（与长轮询匹配） |
| writeTimeoutMs | 35000 | OkHttp 写入超时 |
| httpMaxRetries | 3 | HTTP 重试次数 |
| retryBaseDelayMs | 1000 | 重试基础延迟 |
| retryMaxDelayMs | 10000 | 重试最大延迟 |
| loginTimeoutMs | 180000 | 登录二维码有效期 |
| heartbeatEnabled | true | 是否启用心跳 |
| heartbeatIntervalMs | 30000 | 心跳间隔 |
| ioCoreThreads | 4 | IO 线程池核心线程数 |
| ioMaxThreads | 8 | IO 线程池最大线程数 |
| schedulerThreads | 2 | 定时任务线程数 |
| queueCapacity | 1000 | IO 线程池队列容量 |
| channelVersion | "1.0.0" | 渠道版本号 |
| routeTag | null | 路由标签（SKRouteTag header） |

## 线程模型

```
ilink-io (4-8 线程)
  ├── 登录轮询 (CompletableFuture.supplyAsync)
  └── 其他短任务

ilink-scheduler (2 线程)
  └── 心跳定时任务 (scheduleWithFixedDelay)

ilink-poller (1 线程，项目自建)
  └── getUpdates() 阻塞长轮询

msg-handler (1 线程，项目自建)
  └── 消息处理（避免阻塞 poller）
```

## 异常体系

| 异常 | 触发条件 | 项目处理 |
|------|----------|----------|
| `SessionExpiredException` | API 返回 ret/errcode = -14 | 调用 tryRelogin() 重新扫码 |
| `NotLoginException` | loginContext 为 null 时调用 API | 不应发生 |
| `ConnectFailedException` | HTTP 重试耗尽 | 打印日志，5秒后重试 |
| `MediaUploadException` | 媒体上传/下载/加解密失败 | 打印日志 |
| `ProtocolException` | API 返回非 0 错误码 | 打印日志 |

## 设计师的注释（源码中的有趣发现）

ILinkConfig.java 中有两个有趣的字段注释：
- `routeTag` — 「好像没用」
- `autoReconnectEnabled` — 「好像不太适用」

说明 SDK 作者自己也不确定某些功能是否有实际用途。

## 项目中的用法 (ILinkBot.java)

项目封装了 SDK 的门面类，主要增强：
1. **自动重登录** — `getUpdates()` 检测到 expired/session expired 错误时自动调用 `tryRelogin()`
2. **消息转换** — `WeixinMessage → BotMessage`，解析 voice/image/file/text 四种类型
3. **媒体下载** — 自动下载语音/图片/文件到内存
4. **发送简化** — 高级 API `sendText/sendImage/sendFile/sendTextWithTyping/sendVoiceWithText`

## 与 [[project-architecture]] 的关系

SDK 提供了底层的微信通信能力，项目的 `ILinkBot.java` 是一个薄封装层。
上层 `BotApp.java` 通过 `ILinkBot.setHandler()` 注册消息处理器，在 `msg-handler` 线程中执行业务逻辑。
所有 AI 对话、工具调用等都在 handler 线程中同步完成，只有图片生成是异步的（IMAGE_EXECUTOR）。

**Why:** 理解 SDK 内部机制才能在调试和扩展时做出正确决策
**How to apply:** 
- 遇到 SessionExpiredException 时 SDK 已内置处理；项目层做了额外兜底
- 发消息前确保有 context_token（需先收到用户消息才能回复）
- 媒体上传是 AES-ECB 加密的，密钥由客户端生成
- pollLock 意味着不能同时有两个线程在调用 getUpdates()
