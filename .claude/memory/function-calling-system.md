---
name: function-calling-system
description: Function Calling 系统 — 工具注册、执行、多轮循环
metadata:
  type: project
---

## Function Calling 系统

### 核心流程（processTextMessage）
```
用户文字消息
  → ① tryHandleLocalCommand() — 精确匹配：帮助/人设/音色/语音模式（零 API）
  → ② wantsVoice 检测 — 关键词命中即生效，不调 AI
  → ③ buildTools() — 动态构建工具列表
  → ④ ai.chatWithTools() — 一次 API 调用，AI 自主选工具
  → ⑤ 降级：ai.chat() 自由对话
```

### chatWithTools() 多轮循环（DeepSeekAiServiceImpl）
- 支持最多 MAX_FC_ROUNDS=5 轮
- 每轮：AI 返回 tool_calls → 循环执行全部工具 → 发回结果 → AI 决定是否再调工具
- 无 tool_calls 时返回最终文本
- 返回 null 时降级到自由对话

### buildTools() 注册的工具（BotApp.java）
| 工具名 | 可用条件 | 执行者 |
|--------|----------|--------|
| get_weather | 始终 | WeatherBotService.query() |
| generate_image | imageGen != null | 异步生图 → IMAGE_EXECUTOR |
| get_news | 始终 | NewsService.getNews() → 缓存到 LAST_NEWS |
| read_news_article | news.isAvailable() | NewsService.getArticleDetail() |
| create_session | 始终 | SessionManager |
| switch_session | 始终 | SessionManager |
| delete_session | 始终 | SessionManager |
| list_sessions | 始终 | SessionManager |
| ask_about_image | 有缓存图片 + vision可用 | VisionService.analyze() |
| ask_about_document | 有缓存文档 | 返回文档内容给 AI |
| get_datetime | 始终 | DateTimeService.query() |

### 工具定义辅助方法
- `functionDef(name, description, properties)` — 构建 FunctionDefinition
- 工具定义在 buildTools() 中，执行逻辑作为 lambda 放入 executors Map

**Why:** Function Calling 是项目的核心创新点，替代了旧的两段式意图识别
**How to apply:** 加新工具时在 buildTools() 追加一段：tools.add(functionDef(...)) + executors.put(name, lambda)
