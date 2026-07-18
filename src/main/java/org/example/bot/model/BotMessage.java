package org.example.bot.model;

/**
 * 微信消息的统一数据载体 — 支持纯文字和含图片两种类型。
 *
 * <p>使用工厂方法创建：
 * <ul>
 *   <li>{@link #text(String, String)} — 纯文字消息</li>
 *   <li>{@link #image(String, byte[], String)} — 含图片消息</li>
 * </ul>
 *
 * <p>用 {@link #isImage()} 判断消息类型。
 */
public class BotMessage {

    private final String userId;
    private final String text;
    private final byte[] imageBytes;
    private final String imageCaption;

    private BotMessage(String userId, String text, byte[] imageBytes, String imageCaption) {
        this.userId = userId;
        this.text = text;
        this.imageBytes = imageBytes;
        this.imageCaption = imageCaption;
    }

    /** 创建纯文字消息 */
    public static BotMessage text(String userId, String text) {
        return new BotMessage(userId, text, null, null);
    }

    /** 创建含图片的消息（text 为图片附带的文字说明，可能为空） */
    public static BotMessage image(String userId, byte[] imageBytes, String text) {
        return new BotMessage(userId, text != null ? text : "", imageBytes, null);
    }

    // ---- getters ----

    public String userId() { return userId; }
    public String text() { return text; }

    /** 图片字节（纯文字消息时返回 {@code null}） */
    public byte[] imageBytes() { return imageBytes; }

    /** 是否为图片消息 */
    public boolean isImage() { return imageBytes != null && imageBytes.length > 0; }
}
