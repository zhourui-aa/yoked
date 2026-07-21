package weather;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentService {
    private final ILinkWeatherBot bot;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final int MAX_CHARS = 8000;

    public DocumentService(ILinkWeatherBot bot) {
        this.bot = bot;
    }

    public void handleDocumentMessage(String fromUserId, MessageItem item) {
        String fileName = item.getFile_item() != null ? item.getFile_item().getFile_name() : "未知文件";
        System.out.println("  📎 收到文档: " + fileName);

        try {
            bot.sendReply(fromUserId, "📄 收到文档《" + fileName + "》，正在处理...");
        } catch (Throwable e) {
            System.err.println("❌ 发送确认失败: " + e.getMessage());
        }

        executor.submit(() -> {
            try {
                processDocument(fromUserId, item, fileName);
            } catch (Throwable e) {
                System.err.println("❌ 文档处理异常: " + e.getMessage());
                e.printStackTrace();
                try {
                    bot.sendReply(fromUserId, "❌ 文档处理失败: " + e.getMessage());
                } catch (Throwable ignored) {}
            }
        });
    }

    private void processDocument(String fromUserId, MessageItem item, String fileName) throws Exception {
        String url = bot.extractMediaUrl(item.getFile_item());
        if (url == null) {
            bot.sendReply(fromUserId, "❌ 无法获取文件下载链接");
            return;
        }

        // 1. 下载数据
        byte[] downloadedBytes = downloadBytes(url);
        System.out.println("  ⬇️ 文档下载完成: " + downloadedBytes.length + " 字节");

        // 2. 判断是否需要解密
        byte[] fileBytes;
        Integer encryptType = getEncryptType(item.getFile_item());
        if (encryptType != null && encryptType == 0) {
            System.out.println("  📄 文件未加密，直接使用");
            fileBytes = downloadedBytes;
        } else {
            fileBytes = decryptFile(item.getFile_item(), downloadedBytes);
        }
        System.out.println("  🔓 处理完成: " + fileBytes.length + " 字节");

        // 3. 提取文本
        String content = extractText(fileBytes, fileName);
        if (content == null) {
            bot.sendReply(fromUserId, "❌ 暂不支持该格式，目前支持 .txt / .docx");
            return;
        }
        if (content.trim().isEmpty()) {
            bot.sendReply(fromUserId, "❌ 文档内容为空");
            return;
        }
        System.out.println("  📝 提取文字: " + content.length() + " 字");

        // 4. 截断 + AI 总结
        boolean truncated = false;
        if (content.length() > MAX_CHARS) {
            content = content.substring(0, MAX_CHARS);
            truncated = true;
        }

        bot.sendReply(fromUserId, "🧠 正在总结文档...");
        String summary = bot.getAiService().summarizeDocument(content, fileName);

        StringBuilder reply = new StringBuilder();
        reply.append("📋 《").append(fileName).append("》总结\n");
        reply.append("━━━━━━━━━━━━━━━\n");
        if (truncated) reply.append("⚠️ 文档较长，仅总结前 ").append(MAX_CHARS).append(" 字\n\n");
        reply.append(summary);
        bot.sendReply(fromUserId, reply.toString());
    }

    private Integer getEncryptType(Object fileItem) {
        if (fileItem == null) return null;
        try {
            Object media = fileItem.getClass().getMethod("getMedia").invoke(fileItem);
            if (media == null) return null;
            java.lang.reflect.Method m = media.getClass().getMethod("getEncrypt_type");
            return (Integer) m.invoke(media);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密 CDN 下载的文件。
     * 微信 iLink 协议：所有媒体使用 AES-128-ECB (PKCS7 填充) 加密。
     * aes_key 编码因媒体类型而异：
     *   - 图片: base64(raw 16 bytes)
     *   - 文件/语音/视频: base64(hex string of 16 bytes)
     */
    private byte[] decryptFile(Object fileItem, byte[] encryptedData) throws Exception {
        String aesKeyStr = extractAesKeyRecursive(fileItem);
        if (aesKeyStr == null || aesKeyStr.isEmpty()) {
            System.out.println("  ⚠️ 未找到 aeskey，尝试直接返回原始数据");
            return encryptedData;
        }

        System.out.println("  🔑 原始密钥: " + aesKeyStr.substring(0, Math.min(20, aesKeyStr.length())) + "...");
        System.out.println("  🔑 密钥长度: " + aesKeyStr.length());

        // 解析 aes_key：兼容图片和文件两种编码格式
        byte[] aesKey = parseAesKey(aesKeyStr);
        System.out.println("  🔑 解析后密钥长度: " + aesKey.length + " 字节");
        System.out.println("  🔑 密钥hex: " + bytesToHex(aesKey));

        if (aesKey.length != 16) {
            System.err.println("  ❌ 密钥长度不是16字节，无法使用 AES-128");
            return encryptedData;
        }

        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(encryptedData);

            // 验证解密结果是否是有效文件
            if (isValidDecryptedFile(decrypted)) {
                System.out.println("  ✅ AES-128-ECB 解密成功, 大小: " + decrypted.length);
                return decrypted;
            } else {
                System.out.println("  ⚠️ 解密后数据格式异常，可能密钥错误");
            }
        } catch (Exception e) {
            System.err.println("  ❌ AES-128-ECB 解密失败: " + e.getMessage());
        }

        System.out.println("  ❌ 解密失败，返回原始数据（后续可能解析失败）");
        return encryptedData;
    }

    /**
     * 解析 aes_key，兼容两种编码格式：
     * 1. 图片: base64(raw 16 bytes) -> 直接得到16字节密钥
     * 2. 文件/语音/视频: base64(hex string of 16 bytes) -> 先base64解码得到32字符hex，再hex解码得到16字节密钥
     */
    private byte[] parseAesKey(String aesKeyBase64) {
        // 第一步：base64 解码
        byte[] decoded = java.util.Base64.getDecoder().decode(aesKeyBase64);

        // 尝试作为字符串读取（文件类型是 base64 编码的 hex 字符串）
        String decodedStr = new String(decoded, StandardCharsets.UTF_8);

        // 检查是否是 32 字符的 hex 字符串（文件/语音/视频类型）
        if (decodedStr.matches("^[0-9a-fA-F]{32}$")) {
            System.out.println("  🔑 检测到文件类型密钥编码 (base64(hex_string))");
            return hexToBytes(decodedStr);
        }

        // 如果解码后正好是 16 字节（图片类型：base64(raw 16 bytes)）
        if (decoded.length == 16) {
            System.out.println("  🔑 检测到图片类型密钥编码 (base64(raw_bytes))");
            return decoded;
        }

        // 兜底：如果解码后长度不是16，但内容看起来像hex（可能有空白字符等）
        String trimmed = decodedStr.trim();
        if (trimmed.matches("^[0-9a-fA-F]{32}$")) {
            System.out.println("  🔑 检测到文件类型密钥编码 (trimmed hex)");
            return hexToBytes(trimmed);
        }

        // 无法识别，返回原始解码结果（后续会检查长度）
        System.out.println("  ⚠️ 无法识别密钥编码格式，返回原始解码结果 (长度: " + decoded.length + ")");
        return decoded;
    }

    /**
     * 检查解密后的数据是否是有效的文件格式
     */
    private boolean isValidDecryptedFile(byte[] data) {
        if (data == null || data.length < 4) return false;

        // ZIP/OOXML 格式（docx/xlsx/pptx 等）以 "PK" 开头
        if (data[0] == 'P' && data[1] == 'K') {
            return true;
        }

        // 文本文件：检查是否包含可识别的文本标记
        int previewLen = Math.min(200, data.length);
        String preview = new String(data, 0, previewLen, StandardCharsets.UTF_8);
        if (preview.contains("<?xml") || preview.contains("<w:document") || preview.contains("<html")) {
            return true;
        }

        // 纯文本：检查是否主要是可打印字符
        int printable = 0;
        for (int i = 0; i < previewLen; i++) {
            byte b = data[i];
            if (b == '\n' || b == '\r' || b == '\t' || (b >= 32 && b < 127) || (b & 0xFF) >= 0x80) {
                printable++;
            }
        }
        return (double) printable / previewLen > 0.8;
    }

    /**
     * 递归查找 aeskey：先查对象本身，再查 getMedia() 返回的 CDNMedia
     */
    private String extractAesKeyRecursive(Object obj) {
        if (obj == null) return null;

        // 1. 查所有方法，名字包含 "aes" 或 "key"
        for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
            String name = m.getName().toLowerCase();
            if ((name.contains("aes") || name.contains("key"))
                    && m.getParameterCount() == 0) {
                try {
                    Object val = m.invoke(obj);
                    if (val != null) {
                        String s = val.toString();
                        if (s.length() >= 16) return s;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 2. 查所有字段，名字包含 "aes" 或 "key"
        for (java.lang.reflect.Field field : obj.getClass().getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            if (name.contains("aes") || name.equals("key")) {
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);
                    if (val != null) {
                        String s = val.toString();
                        if (s.length() >= 16) return s;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3. 递归查 getMedia()
        try {
            java.lang.reflect.Method getMedia = obj.getClass().getMethod("getMedia");
            Object media = getMedia.invoke(obj);
            if (media != null && media != obj) {
                String key = extractAesKeyRecursive(media);
                if (key != null) return key;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private String extractText(byte[] data, String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt")) {
            return new String(data, StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".docx")) {
            return extractDocxText(data);
        }
        return null;
    }

    private String extractDocxText(byte[] data) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText().trim();
        } catch (Throwable e) {
            System.err.println("❌ 解析 Word 失败: " + e.getMessage());
            return null;
        }
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        if (conn.getResponseCode() != 200) {
            throw new IOException("下载文件失败 HTTP " + conn.getResponseCode());
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
        }
        return baos.toByteArray();
    }
}