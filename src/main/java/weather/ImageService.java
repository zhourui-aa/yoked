package weather;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ImageService {
    private final ILinkWeatherBot bot;

    public ImageService(ILinkWeatherBot bot) {
        this.bot = bot;
    }

    // ==================== 图片生成（文生图）====================

    public boolean isDrawCommand(String text) {
        String lower = text.toLowerCase().trim();
        return lower.startsWith("画")
                || lower.startsWith("画图")
                || (lower.startsWith("生成") && lower.contains("图"));
    }

    public void handleDrawCommand(String fromUserId, String text) {
        String lower = text.toLowerCase().trim();
        String prompt;
        if (lower.startsWith("画图")) {
            prompt = text.substring(2).trim();
        } else if (lower.startsWith("生成图片")) {
            prompt = text.substring(4).trim();
        } else {
            prompt = text.substring(1).trim();
        }

        if (prompt.isEmpty()) {
            bot.sendReply(fromUserId, "❌ 请提供图片描述\n💡 例如：画一只穿着宇航服的橘猫在月球上");
            return;
        }

        bot.sendReply(fromUserId, "🎨 正在生成图片，请稍候...\n📝 " + prompt);

        try {
            AiService aiService = bot.getAiService();
            String currentModel = aiService.getModelName();
            String genModel = currentModel.contains("wan2.7-image") ? currentModel : "wan2.7-image";
            String size = genModel.contains("pro") ? "2048*2048" : "1024*1024";

            String imageUrl = aiService.generateImage(prompt, size, genModel);
            String caption = "🎨 模型: " + genModel + " | 尺寸: " + size;
            sendGeneratedImage(fromUserId, imageUrl, caption);

        } catch (Exception e) {
            System.err.println("❌ 图片生成失败: " + e.getMessage());
            e.printStackTrace();
            bot.sendReply(fromUserId, "❌ 图片生成失败: " + e.getMessage());
        }
    }

    private void sendGeneratedImage(String toUserId, String imageUrl, String caption) {
        boolean sent = false;
        try {
            byte[] imageBytes = downloadImageBytes(imageUrl);
            System.out.println("  ⬇️ 图片下载完成: " + imageBytes.length + " 字节");

            bot.getClient().sendImage(toUserId, imageBytes, "generated.png", caption);
            System.out.println("✅ 通过 SDK sendImage 发送图片成功");
            sent = true;

            if (caption != null && !caption.isEmpty()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                bot.sendReply(toUserId, caption);
            }
        } catch (Exception e) {
            System.err.println("❌ SDK 发送图片失败: " + e.getMessage());
        }

        if (!sent) {
            String msg = (caption != null ? caption + "\n" : "") + "🖼️ " + imageUrl;
            bot.sendReply(toUserId, msg);
        }
    }

    private byte[] downloadImageBytes(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        if (conn.getResponseCode() != 200) {
            throw new IOException("下载图片失败 HTTP " + conn.getResponseCode());
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

    // ==================== 图片识别（图生文）====================

    /**
     * 下载解密图片，返回 base64 Data URI，不发送分析
     */
    public String handleImageMessageAndReturnBase64(String fromUserId, MessageItem item) {
        try {
            byte[] imageBytes = MediaDownloader.downloadImage(item.getImage_item());
            String dataUri = MediaDownloader.toDataUri(imageBytes, "image/jpeg");
            System.out.println("  🖼️ 图片已解密，Data URI: " + dataUri.substring(0, Math.min(60, dataUri.length())) + "...");
            return dataUri;
        } catch (Exception e) {
            System.err.println("❌ 图片下载解密失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 带文本追问的图片分析
     */
    public String analyzeImageWithText(String fromUserId, String base64Image, String text) {
        System.out.println("🖼️ 分析图片（带追问）: " + text);
        System.out.println("🖼️ 使用视觉模型: " + bot.getAiService().getVisionModel());
        try {
            return bot.getAiService().analyzeImage(base64Image, text);
        } catch (Exception e) {
            System.err.println("❌ 图片分析失败: " + e.getMessage());
            return "❌ 图片分析失败: " + e.getMessage();
        }
    }
}