package org.example.bot.service;

/**
 * 图片生成服务接口 — 文字描述 → 图片。
 *
 * <h3>如何接入新的生图 AI</h3>
 * <pre>{@code
 * public class OpenAiImageGenService implements ImageGenService {
 *     public byte[] generate(String prompt) { ... }
 * }
 * }</pre>
 */
public interface ImageGenService {

    /**
     * 根据文字描述生成图片。
     *
     * @param prompt 图片描述（支持中文）
     * @return 生成的图片字节数组
     */
    byte[] generate(String prompt);
}
