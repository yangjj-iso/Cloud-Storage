package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.exception.TranscodeException;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.transcode.TranscodeProperties;
import com.cloudchunk.transcode.TranscodeResult;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

@Component
@RocketMQMessageListener(
        topic = MqTopics.TRANSCODE,
        selectorExpression = MqTopics.TAG_IMG,
        consumerGroup = MqTopics.CG_TRANSCODE_IMG,
        consumeThreadNumber = 8,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class ImageTranscodeConsumer extends AbstractTranscodeConsumer {

    private static final String[] LABELS = {"s", "m", "l"};

    @Autowired private TranscodeProperties props;

    @Override
    protected String taskType() { return "image"; }

    @Override
    protected TranscodeResult doTranscode(TranscodeMessage msg) throws Exception {
        BufferedImage origin;
        try (InputStream in = storageFactory.current().get(new GetRequest(msg.getBucket(), msg.getObjectKey()))) {
            origin = ImageIO.read(in);
        }
        if (origin == null) {
            throw TranscodeException.fatal("not a readable image: " + msg.getObjectKey());
        }
        List<Integer> sizes = props.getImage().getSizes();
        if (sizes.size() > LABELS.length) {
            sizes = sizes.subList(0, LABELS.length);
        }
        List<TranscodeResult.Thumb> thumbs = TranscodeResult.newThumbList();
        String firstKey = null;

        for (int i = 0; i < sizes.size(); i++) {
            int dim = sizes.get(i);
            byte[] bytes = writeThumb(origin, dim, props.getImage().getQuality());
            String thumbKey = msg.getObjectKey() + ".thumb." + LABELS[i] + ".jpg";
            storageFactory.current().put(PutRequest.of(
                    msg.getBucket(), thumbKey,
                    new ByteArrayInputStream(bytes), bytes.length, "image/jpeg"));
            thumbs.add(new TranscodeResult.Thumb(LABELS[i], thumbKey, dim));
            if (i == 0) firstKey = thumbKey;
        }

        return TranscodeResult.image()
                .put("width", origin.getWidth())
                .put("height", origin.getHeight())
                .put("thumbnails", thumbs)
                .thumbnail(firstKey);
    }

    private byte[] writeThumb(BufferedImage origin, int dim, double quality) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Thumbnails.of(origin)
                .size(dim, dim)
                .keepAspectRatio(true)
                .outputFormat("jpg")
                .outputQuality(quality)
                .toOutputStream(bos);
        return bos.toByteArray();
    }
}
