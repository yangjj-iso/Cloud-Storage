package com.cloudchunk.transcode.ffmpeg;

import com.cloudchunk.common.exception.TranscodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.cloudchunk.transcode.TranscodeProperties;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class FFmpegExecutor {

    private static final Logger log = LoggerFactory.getLogger(FFmpegExecutor.class);

    @Autowired private TranscodeProperties props;

    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(props.getFfmpegPath(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            log.debug("ffmpeg not available: {}", e.getMessage());
            return false;
        }
    }

    public void run(List<String> args, Duration timeout) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getFfmpegPath());
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream());
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw TranscodeException.retryable("ffmpeg timeout: " + cmd);
            }
            if (p.exitValue() != 0) {
                throw TranscodeException.retryable("ffmpeg exit " + p.exitValue() + ": " + cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw TranscodeException.retryable("ffmpeg interrupted", e);
        } catch (IOException e) {
            throw TranscodeException.retryable("ffmpeg io: " + e.getMessage(), e);
        }
    }

    private void drain(InputStream in) {
        Thread.startVirtualThread(() -> {
            try (in) {
                byte[] buf = new byte[4096];
                while (in.read(buf) > 0) { /* discard */ }
            } catch (IOException ignored) {}
        });
    }
}
