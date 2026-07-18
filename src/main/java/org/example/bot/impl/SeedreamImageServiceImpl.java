package org.example.bot.impl;

import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.model.images.generation.ResponseFormat;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.example.bot.service.ImageGenService;
import org.example.bot.util.ConfigUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 豆包 Seedream 生图服务 — 基于火山引擎 Ark SDK。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ImageGenService gen = new SeedreamImageServiceImpl();
 * byte[] image = gen.generate("一只可爱的猫咪");
 * }</pre>
 */
public class SeedreamImageServiceImpl implements ImageGenService {

    private static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String MODEL = "doubao-seedream-5-0-pro-260628";
    private static final String SIZE = "1K";
    private static final String OUTPUT_FORMAT = "png";
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final ArkService service;
    private final HttpClient httpClient;

    /**
     * @throws IllegalStateException 如果未配置 ark.api.key
     */
    public SeedreamImageServiceImpl() {
        String apiKey = ConfigUtil.get("ark.api.key", "ARK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "未找到火山引擎 API Key。请在 config.properties 中设置 ark.api.key，\n"
                + "或设置环境变量 ARK_API_KEY。\n"
                + "获取方式：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey");
        }

        ConnectionPool pool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        this.service = ArkService.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey.trim())
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .build();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DOWNLOAD_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("[Seedream] ✅ 生图服务已就绪（模型: " + MODEL + "）");
    }

    @Override
    public byte[] generate(String prompt) {
        GenerateImagesRequest request = GenerateImagesRequest.builder()
                .model(MODEL)
                .prompt(prompt)
                .size(SIZE)
                .outputFormat(OUTPUT_FORMAT)
                .responseFormat(ResponseFormat.Url)
                .watermark(false)
                .build();

        ImagesResponse response;
        try {
            response = service.generateImages(request);
        } catch (Exception e) {
            String msg = e.getMessage();
            // 内容安全拦截 → 给用户一个可操作的提示
            if (msg != null && msg.contains("OutputImageSensitiveContentDetected")) {
                throw new RuntimeException(
                    "图片内容被安全策略拦截，请尝试修改描述词后重试。\n"
                    + "建议：避免涉及敏感人物、暴力、色情等内容。");
            }
            if (msg != null && msg.contains("statusCode=400")) {
                throw new RuntimeException("生图请求参数有误，请调整描述词后重试。");
            }
            throw new RuntimeException("Seedream 生图失败: " + msg, e);
        }

        String imageUrl = response.getData().get(0).getUrl();
        System.out.println("[Seedream] 图片已生成: " + imageUrl);

        try {
            return downloadImage(imageUrl);
        } catch (Exception e) {
            throw new RuntimeException("下载生成的图片失败: " + e.getMessage(), e);
        }
    }

    public void close() {
        service.shutdownExecutor();
    }

    private byte[] downloadImage(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("下载图片失败，HTTP " + response.statusCode());
        }

        byte[] data = response.body();
        System.out.println("[Seedream] 图片已下载: " + (data.length / 1024) + " KB");
        return data;
    }
}
