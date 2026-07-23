package org.example.bot.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.DashScopeResult;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.protocol.ServiceOption;
import com.alibaba.dashscope.utils.Constants;
import org.example.bot.service.SpeechService;
import org.example.bot.util.ConfigUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class QwenTtsSpeechServiceImpl implements SpeechService {

    private static final String MODEL = "sambert-zhichu-v1";
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final String apiKey;
    private final Map<String, String> voiceLibrary = new LinkedHashMap<>();
    private String currentVoice = "Cherry";

    public QwenTtsSpeechServiceImpl() {
        this.apiKey = ConfigUtil.get("dashscope.api.key", "DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未找到 DashScope API Key。请设置 DASHSCOPE_API_KEY。");
        }

        // 关键：设置全局 API Key
        Constants.apiKey = apiKey.trim();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DOWNLOAD_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 注册音色...
        voiceLibrary.put("Cherry", "温柔女声");
        voiceLibrary.put("Serena", "成熟女声");
        voiceLibrary.put("Sunny", "活泼女声");
        voiceLibrary.put("Stella", "甜美女声");
        voiceLibrary.put("Ethan", "磁性男声");
        voiceLibrary.put("Roy", "沉稳男声");
        voiceLibrary.put("Aiden", "阳光男声");
        voiceLibrary.put("Momo", "可爱女声");
        voiceLibrary.put("Kai", "少年音");
        voiceLibrary.put("Bunny", "俏皮女声");
        voiceLibrary.put("Mia", "清新女声");
        voiceLibrary.put("Mochi", "奶萌音");
        voiceLibrary.put("Pip", "元气少女");
        voiceLibrary.put("Kiki", "萝莉音");

        System.out.println("[TTS] Qwen TTS 已就绪（默认音色: " + currentVoice + "）");
    }

    @Override
    public byte[] textToSpeech(String text) {
        try {
            // 直接用 HTTP 调用 DashScope TTS API
            String requestBody = String.format(
                    "{\"model\":\"%s\",\"input\":{\"text\":\"%s\"},\"parameters\":{\"voice\":\"%s\"}}",
                    MODEL, text.replace("\"", "\\\""), currentVoice
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/audio/tts/sync"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("TTS API 错误: " + response.body());
            }

            // 解析 JSON 获取音频 URL 或 base64
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            String audioUrl = json.getAsJsonObject("output").get("audio_url").getAsString();

            return downloadAudio(audioUrl);

        } catch (Exception e) {
            throw new RuntimeException("TTS 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void setVoice(String voiceId) {
        if (voiceLibrary.containsKey(voiceId)) {
            currentVoice = voiceId;
            System.out.println("[TTS] 音色: " + voiceId);
        } else {
            throw new RuntimeException("未知音色: " + voiceId + "。用「查看音色库」查看可用音色。");
        }
    }

    @Override
    public String getCurrentVoice() {
        return currentVoice;
    }

    @Override
    public String listVoices() {
        StringBuilder sb = new StringBuilder("🎤 音色库：\n");
        for (var e : voiceLibrary.entrySet()) {
            sb.append(e.getKey().equals(currentVoice) ? "  ● " : "  ○ ");
            sb.append(e.getKey()).append(" — ").append(e.getValue());
            if (e.getKey().equals(currentVoice)) sb.append(" ← 当前");
            sb.append("\n");
        }
        sb.append("\n切换音色：切换音色 <名称>");
        return sb.toString();
    }

    private AudioParameters.Voice parseVoice(String v) {
        try {
            return AudioParameters.Voice.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AudioParameters.Voice.CHERRY;
        }
    }

    private byte[] downloadAudio(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        byte[] data = resp.body();
        System.out.println("[TTS] 音频: " + (data.length / 1024) + " KB");
        return data;
    }
}