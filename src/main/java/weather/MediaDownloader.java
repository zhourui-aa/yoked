package weather;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MediaDownloader {
    private static final String CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    /**
     * 下载并解密图片（优先使用 image_item.aeskey）
     */
    public static byte[] downloadImage(Object imageItem) throws Exception {
        Object media = invokeGetter(imageItem, "getMedia");
        if (media == null) throw new RuntimeException("image_item.media 为空");

        String encryptParam = getFieldValue(media, "encrypt_query_param", "encryptQueryParam");
        if (encryptParam == null || encryptParam.isEmpty()) {
            throw new RuntimeException("缺少 encrypt_query_param");
        }

        // 优先 image_item.aeskey，否则 media.aes_key
        String rawAesKey = getFieldValue(imageItem, "aeskey", "aesKey");
        String source = "image_item.aeskey";
        if (rawAesKey == null || rawAesKey.isEmpty()) {
            rawAesKey = getFieldValue(media, "aes_key", "aesKey");
            source = "media.aes_key";
        }

        System.out.println("  🔑 原始密钥来源: " + source);
        System.out.println("  🔑 原始密钥值: " + rawAesKey);
        System.out.println("  🔑 原始密钥长度: " + (rawAesKey != null ? rawAesKey.length() : 0));

        String aesKeyHex = parseAesKey(rawAesKey);
        System.out.println("  🔑 解析后 hex: " + aesKeyHex);
        System.out.println("  🔑 解析后长度: " + (aesKeyHex != null ? aesKeyHex.length() : 0));

        if (aesKeyHex == null) {
            throw new RuntimeException("无法解析 AES 密钥，原始值: " + rawAesKey);
        }

        return downloadAndDecrypt(encryptParam, aesKeyHex);
    }

    /**
     * 下载并解密通用媒体
     */
    public static byte[] downloadMedia(Object mediaItem) throws Exception {
        Object media = invokeGetter(mediaItem, "getMedia");
        if (media == null) throw new RuntimeException("media 为空");

        String encryptParam = getFieldValue(media, "encrypt_query_param", "encryptQueryParam");
        String rawAesKey = getFieldValue(media, "aes_key", "aesKey");
        String aesKeyHex = parseAesKey(rawAesKey);

        if (encryptParam == null || aesKeyHex == null) {
            throw new RuntimeException("缺少加密参数或密钥");
        }

        return downloadAndDecrypt(encryptParam, aesKeyHex);
    }

    /**
     * 智能解析 AES 密钥，支持多种格式
     */
    private static String parseAesKey(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // 1. 如果是 32/48/64 字符纯 hex，直接返回
        if (isHexString(raw) && (raw.length() == 32 || raw.length() == 48 || raw.length() == 64)) {
            System.out.println("  ✅ 检测到纯 hex 字符串");
            return raw;
        }

        // 2. 尝试 base64 解码
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            System.out.println("  ✅ base64 解码成功，长度: " + decoded.length);

            // 2a. 解码后正好是 16/24/32 字节 → 转成 hex
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return bytesToHex(decoded);
            }

            // 2b. 解码后是 ASCII hex 字符串（如 32 字节解码成 "001122..."）
            String asString = new String(decoded, StandardCharsets.UTF_8);
            if (isHexString(asString) && (asString.length() == 32 || asString.length() == 48 || asString.length() == 64)) {
                System.out.println("  ✅ base64 内嵌 hex 字符串");
                return asString;
            }

            // 2c. 其他长度，尝试直接当 raw key 转 hex（可能不是标准长度，但试试）
            return bytesToHex(decoded);

        } catch (IllegalArgumentException e) {
            // 不是 base64
        }

        // 3. 尝试直接 UTF-8 bytes 转 hex（兜底）
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        System.out.println("  ⚠️ 兜底: 直接 UTF-8 字节转 hex，长度: " + rawBytes.length);
        return bytesToHex(rawBytes);
    }

    private static byte[] downloadAndDecrypt(String encryptQueryParam, String aesKeyHex) throws Exception {
        String downloadUrl = CDN_BASE_URL + "/download?encrypted_query_param="
                + URLEncoder.encode(encryptQueryParam, StandardCharsets.UTF_8);

        System.out.println("  ⬇️ 下载: " + downloadUrl.substring(0, Math.min(80, downloadUrl.length())) + "...");

        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("CDN 下载失败 HTTP " + code);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
        }

        byte[] encrypted = baos.toByteArray();
        System.out.println("  ⬇️ 下载完成: " + encrypted.length + " 字节");

        // AES-128-ECB 解密
        byte[] keyBytes = hexToBytes(aesKeyHex);
        if (keyBytes == null) {
            throw new RuntimeException("hexToBytes 返回 null");
        }
        System.out.println("  🔑 密钥字节长度: " + keyBytes.length);

        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            System.err.println("  ⚠️ 密钥长度非标准(16/24/32)，尝试用前 16 字节");
            byte[] truncated = new byte[16];
            System.arraycopy(keyBytes, 0, truncated, 0, Math.min(keyBytes.length, 16));
            keyBytes = truncated;
        }

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);

        byte[] decrypted = cipher.doFinal(encrypted);
        System.out.println("  🔓 解密完成: " + decrypted.length + " 字节");

        // 验证文件头
        if (isValidImage(decrypted)) {
            System.out.println("  ✅ 图片格式验证通过");
        } else {
            System.err.println("  ⚠️ 解密后不是标准图片格式，可能是密钥错误");
            // 打印前 20 字节用于调试
            StringBuilder sb = new StringBuilder("  前20字节: ");
            for (int i = 0; i < Math.min(20, decrypted.length); i++) {
                sb.append(String.format("%02x ", decrypted[i]));
            }
            System.err.println(sb);
        }

        return decrypted;
    }

    private static boolean isValidImage(byte[] data) {
        if (data == null || data.length < 10) return false;
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) return true; // JPEG
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50) return true;  // PNG
        if (data[0] == (byte) 0x47 && data[1] == (byte) 0x49) return true;  // GIF
        if (data[0] == (byte) 0x52 && data[1] == (byte) 0x49) return true;  // WEBP
        return false;
    }

    private static boolean isHexString(String s) {
        if (s == null || s.length() % 2 != 0) return false;
        for (char c : s.toCharArray()) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 安全的 hex 转 bytes，奇数长度自动截断最后一位
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        int len = hex.length();
        if (len == 0) return new byte[0];

        // 奇数长度：截掉最后一位，避免越界
        if (len % 2 != 0) {
            System.err.println("  ⚠️ hex 字符串长度为奇数(" + len + ")，截断最后一位");
            hex = hex.substring(0, len - 1);
            len = hex.length();
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new RuntimeException("非法 hex 字符在位置 " + i + ": '" + hex.charAt(i) + hex.charAt(i+1) + "'");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String toDataUri(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        return "data:" + mimeType + ";base64," + base64;
    }

    private static Object invokeGetter(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getFieldValue(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                Field field = obj.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object val = field.get(obj);
                if (val != null) return val.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }
}