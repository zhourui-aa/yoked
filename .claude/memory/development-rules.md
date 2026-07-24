---
name: development-rules
description: 代码修改规则 — 只加不删、接口优先、与队友协作
metadata:
  type: project
---

## 核心规则

### 1. 只加不删
- 加新功能用新文件、新方法，**不删现有代码**
- 接口方法可以标记 `@Deprecated` 但不要删除
- 组员分支可能引用了旧方法，删除会导致合并冲突
- 统一清理死代码前先沟通确认没人用了

### 2. 服务注册模式
- 新服务：定义接口→写实现→BotApp中初始化→buildTools中注册FC工具
- 需要 API Key 的服务：构造器抛 IllegalStateException → main() 中 try-catch → null 优雅降级
- 不需要 API Key 的服务（如 NewsService）：直接 new，永远可用

### 3. 当前问题：方法签名膨胀
- `processTextMessage()`, `handleVoice()`, `buildTools()` 的参数列表随服务增加而膨胀
- 组员各加各的参数会导致合并冲突
- **未来方向**：将 BotApp 改为实例类，服务作为成员字段（已讨论但暂未实施）
- [[parameter-problem]]

### 4. 配置管理
- ConfigUtil.get(propertyKey, envKey) — 三级优先级：-D参数 > 环境变量 > config.properties
- config.properties 在 .gitignore 中，不提交 API Key
- 新服务配置加在 config.properties 末尾

**Why:** 避免合并冲突，保护队友工作成果
**How to apply:** 每次改代码前先确认"这是在加还是在删？会不会影响队友的分支？"
