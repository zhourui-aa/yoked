package org.example.bot;

/**
 * 微信消息的简化数据载体。
 *
 * <p>把 SDK 原始类型（{@code WeixinMessage}、{@code MessageItem}、{@code TextItem}）
 * 转换成只包含我们需要的信息：谁发的（userId）和发了什么文字（text）。
 *
 * <p>使用 Java {@code record} 的好处：
 * <ul>
 *   <li>代码极短，一眼看清结构</li>
 *   <li>不可变，线程安全</li>
 *   <li>自动生成构造器、getter、equals、hashCode、toString</li>
 * </ul>
 *
 * @param userId 发送者的微信用户 ID（例如 {@code o9cq807l_ou2qFFpvOTKtNMlIBzo@im.wechat}）
 * @param text   消息中提取的文本内容（只包含文字部分，图片/文件/语音等会被过滤）
 */
public record BotMessage(String userId, String text) {
}
