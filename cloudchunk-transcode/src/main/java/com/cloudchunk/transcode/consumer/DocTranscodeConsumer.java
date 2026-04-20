package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.exception.TranscodeException;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.transcode.TranscodeResult;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RocketMQMessageListener(
        topic = MqTopics.TRANSCODE,
        selectorExpression = MqTopics.TAG_DOC,
        consumerGroup = MqTopics.CG_TRANSCODE_DOC,
        consumeThreadNumber = 4,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class DocTranscodeConsumer extends AbstractTranscodeConsumer {

    private static final int MAX_EXTRACT_CHARS = 64 * 1024;
    private static final int SUMMARY_LIMIT = 500;

    @Override
    protected String taskType() { return "doc"; }

    @Override
    protected TranscodeResult doTranscode(TranscodeMessage msg) throws Exception {
        Tika tika = new Tika();
        tika.setMaxStringLength(MAX_EXTRACT_CHARS);
        String text;
        try (InputStream in = storageFactory.current().get(new GetRequest(msg.getBucket(), msg.getObjectKey()))) {
            text = tika.parseToString(in);
        } catch (Exception e) {
            throw TranscodeException.retryable("tika parse failed: " + e.getMessage(), e);
        }
        if (text == null) text = "";
        String summary = text.length() > SUMMARY_LIMIT
                ? text.substring(0, SUMMARY_LIMIT) + "..."
                : text;
        return TranscodeResult.doc()
                .put("textLength", text.length())
                .put("textSummary", summary);
    }
}
