package com.weather.service;

import io.github.kasukusakura.silkcodec.SilkCoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class SilkToWavConverter {
    /**
     * 把微信 SILK 语音字节数组转成 WAV 字节数组
     * @param silkBytes  downloadVoiceFromMessageItem 下载到的原始字节
     * @return  可直接发给百炼 ASR 的 WAV 字节
     */
    public static byte[] convert(byte[] silkBytes) throws IOException {
        // 1. SILK → PCM（silk-codec 自动处理 #!SILK_V3 头部）
        ByteArrayInputStream silkIn = new ByteArrayInputStream(silkBytes);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        SilkCoder.decode(silkIn, pcmOut, true, 16000, 0);
        byte[] pcmBytes = pcmOut.toByteArray();

        // 2. PCM → WAV（手动加 44 字节 RIFF 头）
        int sampleRate = 16000;       // silk-codec 默认输出采样率
        short bitsPerSample = 16; // pcm_s16le
        short channels = 1;       // mono 单声道
        return pcmToWav(pcmBytes, sampleRate, bitsPerSample, channels);
    }

    /**
     * 给裸 PCM 数据加标准 WAV 文件头
     */
    private static byte[] pcmToWav(byte[] pcm, int sampleRate,
                                   short bitsPerSample, short channels) {
        int audioLength = pcm.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteArrayOutputStream wav = new ByteArrayOutputStream(audioLength + 44);
        DataOutputStream out = new DataOutputStream(wav);
        try {
            // RIFF header
            out.writeBytes("RIFF");
            out.write(Integer.reverseBytes(audioLength + 36));
            out.writeBytes("WAVE");
            // fmt chunk
            out.writeBytes("fmt ");
            out.write(Integer.reverseBytes(16));       // PCM = 16
            out.write(Short.reverseBytes((short) 1));  // PCM format
            out.write(Short.reverseBytes(channels));
            out.write(Integer.reverseBytes(sampleRate));
            out.write(Integer.reverseBytes(byteRate));
            out.write(Short.reverseBytes((short) blockAlign));
            out.write(Short.reverseBytes(bitsPerSample));
            // data chunk
            out.writeBytes("data");
            out.write(Integer.reverseBytes(audioLength));
            out.write(pcm);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return wav.toByteArray();
    }
}
