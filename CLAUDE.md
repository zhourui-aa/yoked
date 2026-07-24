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
- ilink/ILinkBot.java — 微信 SDK 门面
- service/ — 服务接口（可插拔）
- impl/ — 服务实现
- model/ — BotMessage + 其他数据类

## 开发规则
1. **只加不删** — 加新功能用新文件/新方法，不删现有代码
2. **新服务模式** — service/ 定义接口 → impl/ 写实现 → BotApp 初始化 → buildTools() 注册 FC 工具
3. **所有新功能走 Function Calling** — 不添加硬编码路由

## 记忆文件
见 MEMORY.md 索引
