package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.exception.TranscodeException;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.transcode.TranscodeProperties;
import com.cloudchunk.transcode.TranscodeResult;
import com.cloudchunk.transcode.ffmpeg.FFmpegExecutor;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
@RocketMQMessageListener(
        topic = MqTopics.TRANSCODE,
        selectorExpression = MqTopics.TAG_VIDEO,
        consumerGroup = MqTopics.CG_TRANSCODE_VIDEO,
        consumeThreadNumber = 2,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class VideoTranscodeConsumer extends AbstractTranscodeConsumer {

    @Autowired private FFmpegExecutor ffmpeg;
    @Autowired private TranscodeProperties props;

    @Override
    protected String taskType() { return "video"; }

    @Override
    protected TranscodeResult doTranscode(TranscodeMessage msg) throws Exception {
        if (!ffmpeg.isAvailable()) {
            log.warn("ffmpeg not available, skip transcode: {}", msg.getFileId());
            return TranscodeResult.video().put("skipped", true).put("reason", "ffmpeg not available");
        }

        Path workDir = Files.createTempDirectory("cc-vid-");
        Path src = workDir.resolve("src" + suffixOf(msg.getObjectKey()));
        Path cover = workDir.resolve("cover.jpg");
        try {
            try (InputStream in = new BufferedInputStream(
                    storageFactory.current().get(new GetRequest(msg.getBucket(), msg.getObjectKey())))) {
                Files.copy(in, src);
            }

            ffmpeg.run(List.of(
                    "-y", "-i", src.toString(),
                    "-ss", "00:00:01", "-frames:v", "1", "-q:v", "3",
                    cover.toString()), props.getVideoTaskTimeout());

            String coverKey = msg.getObjectKey() + ".cover.jpg";
            byte[] coverBytes = Files.readAllBytes(cover);
            storageFactory.current().put(PutRequest.of(
                    msg.getBucket(), coverKey,
                    new java.io.ByteArrayInputStream(coverBytes), coverBytes.length, "image/jpeg"));

            return TranscodeResult.video()
                    .put("coverKey", coverKey)
                    .put("codec", "unknown")
                    .thumbnail(coverKey);
        } catch (TranscodeException te) {
            throw te;
        } catch (Exception e) {
            throw TranscodeException.retryable("video transcode failed: " + e.getMessage(), e);
        } finally {
            cleanup(workDir);
        }
    }

    private String suffixOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot) : ".mp4";
    }

    private void cleanup(Path dir) {
        if (dir == null) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
