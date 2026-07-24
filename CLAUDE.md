# CLAUDE.md

## 项目
微信 AI 聊天机器人 (yoked)，基于 wechat-ilink-sdk 2.3.3

## 源码目录
`src/main/java/org/example/bot/`

## 构建
```bash
mvn compile
mvn exec:java -Dexec.mainClass="org.example.bot.BotApp"
```

## 核心架构
- BotApp.java — 主入口（静态 main），组装服务 + 消息路由
- ilink/ — 微信 SDK 门面（ILinkBot 收/发，BotMessage 消息载体，BotCluster 多 Bot 管理）
- service/ — 服务接口（可插拔）
- impl/ — 服务实现（含 BotState 线程安全缓存、SessionManager 多会话）
- tools/ — 工具中心（ToolCenter 注册中心、ToolDefinition 工具定义、ToolCondition 条件评估）
- util/ — ConfigUtil 配置读取

## Bot 集群
- BotCluster.java — 管理多个 ILinkBot 实例（多微信号同时在线）
- 启动时通过 `-Dbots=N` 指定数量，默认 1 个
- 运行时发送「新建bot <名称>」动态新增，服务器终端打印新二维码

## 消息路由
```
msg → handler
  ├─ 语音 → handleVoice() → 提取文字 → processTextMessage(forceVoice=true)
  ├─ 图片 → handleImage() → Vision API → 缓存至 BotState → 回复
  ├─ 文件 → handleFile() → 文本提取(PDF/Word/Excel/TXT) → AI 总结 → 缓存
  └─ 文字 → processTextMessage()
              ├─ ① 本地命令（帮助/人设/语音模式/音色/新建bot）
              ├─ ② 语音意图检测
              ├─ ③ buildTools() → toolCenter.buildTools() → 构建 FC 工具列表
              ├─ ④ ai.chatWithTools() → AI 选工具 → 最多 5 轮
              └─ ⑤ ai.chat() 自由对话
```

## 工具注册（ToolCenter）
所有 FC 工具统一在 `registerAllTools()` 中注册到 `toolCenter`，`buildTools()` 为薄壳委托。

- 无条件注册：天气、新闻、计算器(4)、随机(4)、日期时间、会话管理(4)
- 服务可用时注册：足球(4)、饮食(1)、生图(1)、快递(1)
- per-request 条件：图片追问（有缓存+vision可用）、文档追问（有缓存）、新闻详情（news可用）

## 开发规则
1. **只加不删** — 加新功能用新文件/新方法，不删现有代码
2. **新服务模式** — service/ 定义接口 → impl/ 写实现 → BotApp 初始化 → registerAllTools() 注册 FC 工具
3. **所有新功能走 Function Calling** — 不添加硬编码路由
4. **加新工具** — 在 `registerAllTools()` 中 `toolCenter.register(new ToolDefinition(...))` 一行即可

## 记忆文件
见 MEMORY.md 索引
