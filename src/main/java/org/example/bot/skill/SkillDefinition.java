package org.example.bot.skill;

import java.util.*;

/**
 * Skill 定义 — 从 .md 文件解析出的数据结构。
 *
 * <h3>.md 文件格式</h3>
 * <pre>
 * ---
 * name: greet
 * description: 快捷问候
 * type: shortcut
 * ---
 * 触发词: 回复内容
 * 触发词: 回复内容
 * </pre>
 *
 * <p>正文每行格式：{@code 触发词: 回复}。
 * 对于 augment/post 类型，正文整体作为内容，不需要冒号映射。
 */
public class SkillDefinition {
    private final String name;
    private final String description;
    private final String type;            // "shortcut" | "augment" | "post"
    private final String content;         // 原始正文
    private final Map<String, String> responses; // trigger → response（shortcut）
    private final List<String> triggers;  // 所有触发词
    private final String filePath;

    public SkillDefinition(String name, String description,
                           String type, String content, String filePath) {
        this.name = name;
        this.description = description;
        this.type = (type != null && !type.isBlank()) ? type : "shortcut";
        this.content = content;
        this.filePath = filePath;

        // 解析正文：每行 "触发词: 回复"
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0 && "shortcut".equals(this.type)) {
                String trigger = line.substring(0, colon).strip();
                String response = line.substring(colon + 1).strip();
                if (!trigger.isEmpty() && !response.isEmpty()) {
                    map.put(trigger, response);
                }
            }
        }
        this.responses = Collections.unmodifiableMap(map);
        this.triggers = List.copyOf(map.keySet());
    }

    public String name()            { return name; }
    public String description()     { return description; }
    public String type()            { return type; }
    public String content()         { return content; }
    public String filePath()        { return filePath; }
    public List<String> triggers()  { return triggers; }

    /** 查找匹配的触发词，返回对应的回复（shortcut）；无匹配返回 null */
    public String matchAndReply(String text) {
        for (var e : responses.entrySet()) {
            if (text.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** 检查用户消息是否触发此 Skill（augment/post 用全文匹配） */
    public boolean matches(String text) {
        for (String t : triggers) {
            if (text.contains(t)) return true;
        }
        // augment/post 没有 triggers 时始终生效
        return triggers.isEmpty() && !"shortcut".equals(type);
    }

    @Override
    public String toString() {
        return "SkillDefinition{name='" + name + "', type=" + type
            + ", triggers=" + triggers + "}";
    }
}
