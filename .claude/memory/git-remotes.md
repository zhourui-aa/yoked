---
name: git-remotes
description: Git 远程仓库配置 — 小组和个人仓库地址
metadata:
  type: reference
---

## Git 远程仓库

| 名称 | 仓库地址 | 用途 |
|------|----------|------|
| `origin` | `https://github.com/zhourui-aa/yoked.git` | 小组仓库（main, branch-2, zzx, wh, xzx 等分支） |
| `personal` | `https://github.com/zhang-zi-xu/youdekaSummer19.git` | 个人仓库 |

### 推送目标
通常推送命令：
```bash
git push origin branch-2:branch-2   # 小组 branch-2
git push origin branch-2:main       # 小组 main（覆盖）
git push personal branch-2:main     # 个人 main
```

### 当前工作分支
- 本地：`zzx`（包含新闻功能的最新代码）
- 小组 main：`c897594`（含组员 wh 的 DateTime 合并）
- 最新提交：`805171c Merge branch 'wh' into main`

### 新目录迁移
项目已从 `D:\ykdProjects\wechatTest1` 迁移到 `D:\youkedaSummer`，需要在新目录重新配置 personal 远程。

**Why:** 记住仓库地址，便于推送
**How to apply:** 推送前确认当前分支和目标分支
