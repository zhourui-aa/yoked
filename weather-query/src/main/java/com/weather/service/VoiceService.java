package com.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kasukusakura.silkcodec.SilkCoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

public class VoiceService {
    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 百炼 ASR 接口（多模态生成接口，fun-asr 走这个）
    private static final String ASR_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    // 百炼 TTS 接口
    private static final String TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";

    public VoiceService() {
        // 读配置，跟 ImageAnalyzer/ImageGenerator 一样的套路
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        } catch (Exception e) {
            throw new RuntimeException("无法加载 application.properties", e);
        }
        this.apiKey = props.getProperty("ai.api.key");
    }

    // ==================== 语音识别（ASR）====================

    /**
     * 把音频字节数组转成文字
     * @param silkBytes 音频数据（WAV格式）
     * @return 识别出的文字
     */
    public String recognize(byte[] silkBytes) throws Exception{
        byte[] wavBytes = SilkToWavConverter.convert(silkBytes);
        try {
            // 1. 音频转 Base64 Data URL
            String base64 = Base64.getEncoder().encodeToString(wavBytes);
            String dataUrl = "data:audio/wav;base64," + base64;

            // 2. 拼请求体（fun-asr-flash 走多模态接口）
            Map<String, Object> body = Map.of(
                    "model", "fun-asr-flash-2026-06-15",
                    "input", Map.of(
                            "messages", java.util.List.of(
                                    Map.of(
                                            "role", "user",
                                            "content", java.util.List.of(
                                                    Map.of(
                                                            "type", "input_audio",
                                                            "input_audio", Map.of("data", dataUrl)
                                                    )
                                            )
                                    )
                            )
                    ),
                    "parameters", Map.of(
                            "format", "wav",
                            "sample_rate", "16000"
                    )
            );

            String json = objectMapper.writeValueAsString(body);

            // 3. 发请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ASR_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("[VoiceService/ASR] 响应: "
                    + response.body().substring(0, Math.min(response.body().length(), 200)));

            // 4. 解析返回的文字
            JsonNode root = objectMapper.readTree(response.body());
            // fun-asr 的返回格式：output.choices[0].message.content[0].text
            JsonNode content = root.path("output").path("choices").get(0)
                    .path("message").path("content");
            if (content.isArray() && content.size() > 0) {
                return content.get(0).path("text").asText();
            }
            // 备用：有些版本直接返回 content 字符串
            return root.path("output").path("choices").get(0)
                    .path("message").path("content").asText();

        } catch (Exception e) {
            System.err.println("[VoiceService/ASR] 识别失败: " + e.getMessage());
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        }
    }

    // ==================== 语音合成（TTS）====================

    /**
     * 把文字转成语音字节数组
     * @param text 要合成的文字
     * @return WAV格式的音频数据
     */
    public byte[] synthesize(String text) {
        try {
            // 1. 拼请求体
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", "cosyvoice-v3-flash");
            body.put("input", Map.of(
                    "text", text,
                    "voice", "longanyang",    // 音色名，可以换
                    "format", "wav",
                    "sample_rate", 16000
            ));

            String json = objectMapper.writeValueAsString(body);

            // 2. 发请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("[VoiceService/TTS] 响应: "
                    + response.body().substring(0, Math.min(response.body().length(), 200)));

            // 3. 解析返回的音频 URL
            JsonNode root = objectMapper.readTree(response.body());
            String audioUrl = root.path("output").path("audio").path("url").asText();

            if (audioUrl.isEmpty()) {
                // 备用路径
                audioUrl = root.path("output").path("url").asText();
            }

            if (audioUrl.isEmpty()) {
                throw new RuntimeException("TTS 返回中没有音频URL: " + response.body());
            }

            System.out.println("[VoiceService/TTS] 音频URL: " + audioUrl);

            // 4. 下载音频文件（WAV 格式）
            byte[] wavBytes = downloadFile(audioUrl);
            System.out.println("[VoiceService/TTS] WAV 大小: " + wavBytes.length + " bytes");

            // 5. WAV → SILK（微信语音要求 SILK 格式）
            byte[] silkBytes = wavToSilk(wavBytes);
            System.out.println("[VoiceService/TTS] SILK 大小: " + silkBytes.length + " bytes");
            return silkBytes;

        } catch (Exception e) {
            System.err.println("[VoiceService/TTS] 合成失败: " + e.getMessage());
            throw new RuntimeException("语音合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把文字转成 WAV 音频字节数组（不转 SILK，直接返回 WAV）
     * @param text 要合成的文字
     * @return WAV 格式的音频数据
     */
    public byte[] synthesizeToWav(String text) {
        try {
            // 1. 拼请求体
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", "cosyvoice-v3-flash");
            body.put("input", Map.of(
                    "text", text,
                    "voice", "longanyang",
                    "format", "wav",
                    "sample_rate", 16000
            ));

            String json = objectMapper.writeValueAsString(body);

            // 2. 发请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("[VoiceService/TTS-WAV] 响应: "
                    + response.body().substring(0, Math.min(response.body().length(), 200)));

            // 3. 解析返回的音频 URL
            JsonNode root = objectMapper.readTree(response.body());
            String audioUrl = root.path("output").path("audio").path("url").asText();

            if (audioUrl.isEmpty()) {
                audioUrl = root.path("output").path("url").asText();
            }

            if (audioUrl.isEmpty()) {
                throw new RuntimeException("TTS 返回中没有音频URL: " + response.body());
            }

            System.out.println("[VoiceService/TTS-WAV] 音频URL: " + audioUrl);

            // 4. 下载并返回 WAV 文件
            byte[] wavBytes = downloadFile(audioUrl);
            System.out.println("[VoiceService/TTS-WAV] WAV 大小: " + wavBytes.length + " bytes");
            return wavBytes;

        } catch (Exception e) {
            System.err.println("[VoiceService/TTS-WAV] 合成失败: " + e.getMessage());
            throw new RuntimeException("语音合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载文件到字节数组
     */
    private byte[] downloadFile(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }

    private byte[] wavToSilk(byte[] wavBytes) throws IOException {
        // 1. 验证 WAV 文件头

        // 1. 安全验证
        if (wavBytes == null || wavBytes.length < 44) {
            throw new IOException("WAV 文件太短: " + (wavBytes == null ? 0 : wavBytes.length));
        }

        // 确认是 RIFF
        if (wavBytes[0] != 'R' || wavBytes[1] != 'I' || wavBytes[2] != 'F' || wavBytes[3] != 'F') {
            throw new IOException("TTS 返回的不是 WAV 格式");
        }

        // 2. 读取采样率（offset 24-27，little-endian）
        int sampleRate = (wavBytes[24] & 0xFF) |
                ((wavBytes[25] & 0xFF) << 8) |
                ((wavBytes[26] & 0xFF) << 16) |
                ((wavBytes[27] & 0xFF) << 24);

        System.out.println("[VoiceService/wavToSilk] WAV 大小: " + wavBytes.length +
                ", 采样率: " + sampleRate + "Hz");

        // 3. 标准 WAV 文件头固定 44 字节，后面就是 PCM 数据
        int dataLength = wavBytes.length - 44;
        if (dataLength <= 0) {
            throw new IOException("WAV 数据长度为 0");
        }

        byte[] pcmBytes = new byte[dataLength];
        System.arraycopy(wavBytes, 44, pcmBytes, 0, dataLength);
        System.out.println("[VoiceService/wavToSilk] PCM 大小: " + dataLength + " bytes");

        // 4. PCM → SILK（使用 WAV 中的实际采样率）
        ByteArrayInputStream pcmIn = new ByteArrayInputStream(pcmBytes);
        ByteArrayOutputStream silkOut = new ByteArrayOutputStream();
        SilkCoder.encode(pcmIn, silkOut, sampleRate, 24000, true, false);

        byte[] silkBytes = silkOut.toByteArray();
        System.out.println("[VoiceService/wavToSilk] SILK 大小: " + silkBytes.length + " bytes");

        // 5. 验证 SILK 头部（微信格式: 0x02 + #!SILK_V3）
        if (silkBytes.length >= 10) {
            String header = new String(silkBytes, 1, 9, java.nio.charset.StandardCharsets.US_ASCII);
            System.out.println("[VoiceService/wavToSilk] SILK 头部: 0x02 + " + header);
        } else if (silkBytes.length >= 9) {
            String header = new String(silkBytes, 0, 9, java.nio.charset.StandardCharsets.US_ASCII);
            System.out.println("[VoiceService/wavToSilk] SILK 头部(无0x02前缀): " + header);
        }

        return silkBytes;
    }
}
