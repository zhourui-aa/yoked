package weather;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VoiceService {
    private final ILinkWeatherBot bot;
    private final OkHttpClient httpClient;
    private static final String API_KEY = "sk-ws-H.EDERRRR.G8ME.MEYCIQCQkc1nKAkznZiviFwkMNCWhkhZJta-JgWfpfhJ0jWtNAIhAIY2O8XlHDvK4YHEcq8t6AbbnxaWQjYhSdecSLY-UOA6";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // 微信语音标准采样率
    private static final int SAMPLE_RATE = 16000;

    private boolean voiceReplyEnabled = false;

    public VoiceService(ILinkWeatherBot bot) {
        this.bot = bot;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void setVoiceReplyEnabled(boolean enabled) {
        this.voiceReplyEnabled = enabled;
        System.out.println("🔊 语音回复: " + (enabled ? "开启" : "关闭"));
    }

    public boolean isVoiceReplyEnabled() {
        return voiceReplyEnabled;
    }
//微信替你完成了"声波 → 特征 → 音素 → 文字"的完整 ASR 流程，你的机器人只处理文字
    public void handleVoiceMessage(String fromUserId, String voiceText) {
        if (voiceText != null && !voiceText.isEmpty()) {
            System.out.println("  🎤 语音转文字: [" + voiceText + "]");
            bot.handleTextMessage(fromUserId, voiceText);//当作普通文字处理
        } else {
            try {
                bot.getClient().sendText(fromUserId, "🎤 收到语音，但未能识别内容~");
            } catch (Exception e) {
                System.err.println("❌ 发送失败: " + e.getMessage());
            }
        }
    }

    public boolean trySendVoice(String fromUserId, String text) {
        if (!voiceReplyEnabled || text.length() >= 300) {
            return false;
        }
        try {
            sendVoiceReply(fromUserId, text);
            return true;
        } catch (Exception e) {
            System.err.println("❌ 语音发送失败: " + e.getMessage());
            return false;
        }
    }

    public void sendVoiceReply(String fromUserId, String text) throws Exception {
        // 1. TTS 生成 MP3
        byte[] mp3Bytes = callCosyVoice(text);
        System.out.println("  🔊 TTS 完成: " + mp3Bytes.length + " 字节");

        // 2. 作为文件发送（语音气泡暂不可用，fallback 为文件）
        bot.getClient().sendFile(fromUserId, mp3Bytes, "reply.mp3", "🎙️ AI 语音回复");
        System.out.println("✅ 语音文件发送成功");
    }
//调用阿里云 TTS（文字 → 声学特征 → 音频）
    private byte[] callCosyVoice(String text) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "qwen3-tts-flash"); // ← 大模型 TTS

        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        input.addProperty("voice", "Cherry");// ← 音色
        input.addProperty("language_type", "Chinese");
        requestBody.add("input", input);

        Request request = new Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, requestBody.toString()))
                .build();

        String audioUrl;
        try (Response response = httpClient.newCall(request).execute()) {
            String bodyStr = response.body() != null ? response.body().string() : "null";
            if (!response.isSuccessful()) {
                throw new IOException("TTS HTTP " + response.code() + ": " + bodyStr);
            }

            System.out.println("  📄 TTS 原始响应: " + bodyStr.substring(0, Math.min(200, bodyStr.length())) + "...");

            JsonObject json = com.google.gson.JsonParser.parseString(bodyStr).getAsJsonObject();
            JsonObject output = json.getAsJsonObject("output");
            if (output == null) {
                throw new IOException("TTS 响应缺少 output 字段: " + bodyStr);
            }
            // 解析 JSON 提取音频 URL
            JsonObject audio = output.getAsJsonObject("audio");
            if (audio == null) {
                if (output.has("url")) {
                    audioUrl = output.get("url").getAsString();
                } else {
                    throw new IOException("TTS 响应缺少 audio/url 字段: " + bodyStr);
                }
            } else {
                audioUrl = audio.get("url").getAsString();
            }
        }

        System.out.println("  🔊 TTS 音频 URL: " + audioUrl.substring(0, Math.min(60, audioUrl.length())) + "...");
        // 下载音频文件
        Request downloadReq = new Request.Builder().url(audioUrl).get().build();
        try (Response downloadResp = httpClient.newCall(downloadReq).execute()) {
            if (!downloadResp.isSuccessful()) {
                throw new IOException("下载音频失败 HTTP " + downloadResp.code());
            }
            byte[] audioBytes = downloadResp.body().bytes();  // MP3 二进制
            System.out.println("  ⬇️ 下载音频: " + audioBytes.length + " 字节");
            return audioBytes;
        }
    }

    /**
     * MP3 → PCM → silk，返回 silk 字节和真实时长
     */
    private SilkResult convertToSilk(byte[] mp3Bytes) throws Exception {
        java.io.File ffmpeg = new java.io.File("tools/ffmpeg.exe");
        java.io.File pyScript = new java.io.File("tools/silk_converter.py");
        if (!ffmpeg.exists()) {
            throw new RuntimeException("缺少 tools/ffmpeg.exe");
        }
        if (!pyScript.exists()) {
            throw new RuntimeException("缺少 tools/silk_converter.py");
        }

        String uuid = UUID.randomUUID().toString();
        Path tempDir = Files.createTempDirectory("voice_");
        Path mp3File = tempDir.resolve(uuid + ".mp3");
        Path pcmFile = tempDir.resolve(uuid + ".pcm");
        Path silkFile = tempDir.resolve(uuid + ".silk");

        Files.write(mp3File, mp3Bytes);

        try {
            // Step 1: MP3 → PCM（采样率改为 16000）
            ProcessBuilder pb1 = new ProcessBuilder(
                    ffmpeg.getAbsolutePath(), "-y", "-i", mp3File.toString(),
                    "-ar", String.valueOf(SAMPLE_RATE), "-ac", "1", "-f", "s16le", pcmFile.toString()
            );
            runProcess(pb1, "ffmpeg");

            long pcmSize = Files.size(pcmFile);
            if (pcmSize == 0) {
                throw new RuntimeException("ffmpeg 未生成 PCM 文件");
            }

            // 计算真实时长：16000Hz, 1ch, 16bit = 32000 bytes/sec
            int durationMs = (int) (pcmSize * 1000 / (SAMPLE_RATE * 2));

            // Step 2: PCM → silk（采样率也传 16000）
            ProcessBuilder pb2 = new ProcessBuilder(
                    "python", pyScript.getAbsolutePath(),
                    pcmFile.toString(), silkFile.toString(), String.valueOf(SAMPLE_RATE)
            );
            runProcess(pb2, "pysilk");

            if (!Files.exists(silkFile)) {
                throw new RuntimeException("Python pysilk 转换失败");
            }

            byte[] silkBytes = Files.readAllBytes(silkFile);
            return new SilkResult(silkBytes, Math.max(durationMs, 1000));

        } finally {
            Files.deleteIfExists(mp3File);
            Files.deleteIfExists(pcmFile);
            Files.deleteIfExists(silkFile);
            Files.deleteIfExists(tempDir);
        }
    }

    private void runProcess(ProcessBuilder pb, String name) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(name + " 执行超时");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException(name + " 执行失败 (exit=" + exitCode + "):\n" + output);
        }
    }

    private static class SilkResult {
        final byte[] silkBytes;
        final int durationMs;

        SilkResult(byte[] silkBytes, int durationMs) {
            this.silkBytes = silkBytes;
            this.durationMs = durationMs;
        }
    }
}