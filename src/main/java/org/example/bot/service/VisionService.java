package org.example.bot.service;

/**
 * 图片识别服务接口 — 接收图片，返回对图片内容的理解。
 */
public interface VisionService {

    /**
     * 分析图片内容。
     *
     * @param imageBytes 图片字节数组（PNG/JPEG）
     * @param prompt     对图片的提问，如 "描述这张图片" 或 "图片里有什么？"
     * @return AI 对图片内容的理解描述
     */
    String analyze(byte[] imageBytes, String prompt);
}
