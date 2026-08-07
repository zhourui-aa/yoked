package org.example.bot.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个对话会话 — 持有独立的对话历史和人设。
 */
class Session {
    final String name;
    String persona;
    final List<String> roles = new ArrayList<>();
    final List<String> contents = new ArrayList<>();

    Session(String name, String persona) {
        this.name = name;
        this.persona = persona;
    }

    synchronized void add(String role, String content) {
        roles.add(role);
        contents.add(content);
    }

    synchronized void trim(int maxHistory) {
        while (roles.size() > maxHistory) {
            roles.remove(0);
            contents.remove(0);
        }
    }

    synchronized void clear() {
        roles.clear();
        contents.clear();
    }

    /** 从数据库批量恢复历史（按时间顺序） */
    synchronized void loadFromDb(List<String[]> history) {
        for (String[] entry : history) {
            roles.add(entry[0]);
            contents.add(entry[1]);
        }
    }
}
