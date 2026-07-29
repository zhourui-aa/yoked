package org.example.bot.skill;

import org.example.bot.ilink.ILinkBot;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Skill 管理器 — 扫描 src/skills/ 目录，自动加载 .md 文件。
 *
 * <h3>设计原则</h3>
 * 杜绝 if-else 硬编码：内部用 {@code List<SkillDefinition>} 循环匹配。
 * 加新 Skill = 写一个 .md 丢进 src/skills/，重启即生效。
 *
 * <h3>Skill 类型</h3>
 * <ul>
 *   <li>{@code shortcut} — 关键词命中直接回复，短路 AI 调用</li>
 *   <li>{@code augment}  — 命中时注入上下文到 system prompt</li>
 *   <li>{@code post}     — AI 回复后做后处理</li>
 * </ul>
 */
public class SkillManager {

    private final List<SkillDefinition> skills = new ArrayList<>();
    private final String skillsDir;

    /** @param skillsDir src/skills/ 的路径（相对于项目根目录） */
    public SkillManager(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    // ==================== 扫描加载 ====================

    /** 扫描目录，加载所有 .md 文件 */
    public int loadFromDir() {
        skills.clear();
        Path dir = Paths.get(skillsDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("[SkillManager] ⚠ 目录不存在: " + skillsDir + "，跳过扫描");
            return 0;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path p : stream) {
                try {
                    SkillDefinition def = parseSkillFile(p);
                    if (def != null) skills.add(def);
                } catch (Exception e) {
                    System.err.println("[SkillManager] ⚠ 解析失败: " + p + " — " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[SkillManager] ❌ 扫描目录失败: " + e.getMessage());
        }
        // 按 name 排序保证一致性
        skills.sort(Comparator.comparing(SkillDefinition::name));
        return skills.size();
    }

    /** 解析单个 .md 文件 */
    private SkillDefinition parseSkillFile(Path path) throws IOException {
        String raw = Files.readString(path);
        String[] parts = raw.split("---", 3);
        if (parts.length < 3) {
            System.out.println("[SkillManager] ⚠ " + path.getFileName() + " 缺少 frontmatter，跳过");
            return null;
        }

        Map<String, String> meta = parseFrontmatter(parts[1]);
        String name = meta.get("name");
        if (name == null || name.isBlank()) {
            System.out.println("[SkillManager] ⚠ " + path.getFileName() + " 缺少 name，跳过");
            return null;
        }

        String description = meta.getOrDefault("description", "");
        String type = meta.getOrDefault("type", "shortcut");
        String content = parts[2].strip();

        return new SkillDefinition(name, description, type, content,
            path.toAbsolutePath().toString());
    }

    /** 解析 YAML 风格的简单 frontmatter（key: value，不支持嵌套） */
    private Map<String, String> parseFrontmatter(String fm) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : fm.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                map.put(key, value);
            }
        }
        return map;
    }

    // ==================== 查询 ====================

    public int size() { return skills.size(); }

    public List<SkillDefinition> all() { return Collections.unmodifiableList(skills); }

    /** 查找所有匹配消息的 Skill */
    public List<SkillDefinition> match(String text) {
        List<SkillDefinition> matched = new ArrayList<>();
        for (SkillDefinition s : skills) {
            if (s.matches(text)) matched.add(s);
        }
        return matched;
    }

    public String summary() {
        if (skills.isEmpty()) return "[SkillManager] 0 个 Skill";
        StringBuilder sb = new StringBuilder("[SkillManager] ").append(skills.size()).append(" 个 Skill:\n");
        for (SkillDefinition s : skills) {
            sb.append("  • ").append(s.name()).append(" (").append(s.type())
              .append(") — ").append(s.description()).append("\n");
        }
        return sb.toString().strip();
    }

    // ==================== 管道方法 ====================

    /**
     * 预处理 — 遍历所有 shortcut 类型的 Skill，第一个命中就短路。
     * @return true 表示消息已被处理，无需继续走 AI 流程
     */
    public boolean preProcess(String userId, String text, ILinkBot bot) {
        for (SkillDefinition s : skills) {
            if (!"shortcut".equals(s.type())) continue;
            String reply = s.matchAndReply(text);
            if (reply != null) {
                bot.sendText(userId, reply);
                System.out.println("[Skill:" + s.name() + "] ✅ 短路: " + text + " → " + reply);
                return true;
            }
        }
        return false;
    }

    /**
     * 聚合所有 augment 类型 Skill 的上下文。
     * @return 合并后的增强文本
     */
    public String augmentContext(String userId, String text) {
        StringBuilder sb = new StringBuilder();
        for (SkillDefinition s : skills) {
            if (!"augment".equals(s.type())) continue;
            if (s.matches(text)) {
                sb.append(s.content().strip()).append("\n");
                System.out.println("[Skill:" + s.name() + "] 📎 增强上下文");
            }
        }
        return sb.toString().strip();
    }

    /**
     * 后处理 — 链式调用所有 post 类型的 Skill。
     * @return 处理后的回复
     */
    public String postProcess(String userId, String reply) {
        String result = reply;
        for (SkillDefinition s : skills) {
            if (!"post".equals(s.type())) continue;
            // post 类型始终执行（不依赖 trigger 匹配）
            result = result.replace(s.content().strip(), "");
            System.out.println("[Skill:" + s.name() + "] 🔧 后处理");
        }
        return result;
    }
}
