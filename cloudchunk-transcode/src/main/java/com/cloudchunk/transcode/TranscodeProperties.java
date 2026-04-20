package com.cloudchunk.transcode;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "cloudchunk.transcode")
public class TranscodeProperties {

    private String ffmpegPath = "ffmpeg";
    private Duration videoTaskTimeout = Duration.ofMinutes(30);
    private int videoMaxDurationSeconds = 7200;

    private Image image = new Image();

    public static class Image {
        private List<Integer> sizes = List.of(200, 600, 1200);
        private double quality = 0.85;

        public List<Integer> getSizes() { return sizes; }
        public void setSizes(List<Integer> sizes) { this.sizes = sizes; }
        public double getQuality() { return quality; }
        public void setQuality(double quality) { this.quality = quality; }
    }

    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }
    public Duration getVideoTaskTimeout() { return videoTaskTimeout; }
    public void setVideoTaskTimeout(Duration videoTaskTimeout) { this.videoTaskTimeout = videoTaskTimeout; }
    public int getVideoMaxDurationSeconds() { return videoMaxDurationSeconds; }
    public void setVideoMaxDurationSeconds(int videoMaxDurationSeconds) { this.videoMaxDurationSeconds = videoMaxDurationSeconds; }
    public Image getImage() { return image; }
    public void setImage(Image image) { this.image = image; }
}
