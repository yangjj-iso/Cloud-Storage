package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Consent-gated, exact-source Q&A; no AI tool invocation or implicit permission delegation. */
@Service
public class GroundedAnswerService {
    private static final int MAX_SOURCES = 6;
    private static final Pattern CITATION = Pattern.compile("\\[S([0-9]+)\\]");
    private final KnowledgeService knowledge;
    private final ChatGateway gateway;
    private final boolean enabled;

    public GroundedAnswerService(KnowledgeService knowledge, ChatGateway gateway, Environment env) {
        this.knowledge = knowledge;
        this.gateway = gateway;
        this.enabled = Boolean.parseBoolean(env.getProperty("cloudchunk.ai.chat.enabled","false"));
    }

    public AssistantCapabilities capabilities() {
        return new AssistantCapabilities(enabled && gateway.configured(), "SELECTED_KEYWORD_EVIDENCE");
    }

    public AnswerResponse answer(long userId, AnswerRequest request) {
        if (!capabilities().enabled()) throw BizException.of(ErrorCode.AI_DISABLED);
        if (request == null || !request.consent() || request.question() == null
                || request.question().strip().length() < 4 || request.question().strip().length() > 500
                || request.sources() == null || request.sources().isEmpty()
                || request.sources().size() > MAX_SOURCES) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "问题须为4至500字，选择1至6个来源并明确同意模型发送");
        }
        LinkedHashSet<SourceRef> unique = new LinkedHashSet<>(request.sources());
        if (unique.size() != request.sources().size() || unique.contains(null)) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "来源不能重复或为空");
        }
        List<CitedSource> sources = new ArrayList<>();
        int n = 0;
        for (SourceRef ref : unique) {
            if (ref.userFileId() == null || !ref.userFileId().matches("[0-9]{1,19}")) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid source");
            }
            long id;
            try { id = Long.parseLong(ref.userFileId()); }
            catch (NumberFormatException e) { throw BizException.of(ErrorCode.INVALID_PARAMETER); }
            Evidence evidence = knowledge.source(userId,id,ref.generation(),ref.chunkIndex());
            if (evidence == null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
            sources.add(new CitedSource("S" + (++n), evidence));
        }
        String answer = gateway.complete(request.question().strip(),sources);
        if ("资料不足，无法回答。".equals(answer) || "资料不足，无法回答".equals(answer)) {
            return new AnswerResponse("资料不足，无法回答。",List.of(),"INSUFFICIENT_EVIDENCE");
        }
        if (answer.length() > 2500) throw BizException.of(ErrorCode.AI_UNGROUNDED);
        Matcher matcher = CITATION.matcher(answer);
        Set<Integer> used = new HashSet<>();
        while (matcher.find()) {
            int index;
            try { index = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { throw BizException.of(ErrorCode.AI_UNGROUNDED); }
            if (index < 1 || index > sources.size()) throw BizException.of(ErrorCode.AI_UNGROUNDED);
            used.add(index);
        }
        if (used.isEmpty()) throw BizException.of(ErrorCode.AI_UNGROUNDED);
        List<CitedSource> cited = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (!used.contains(i+1)) continue;
            SourceRef ref = request.sources().get(i);
            CitedSource previous = sources.get(i);
            // Revocation or reindexing during inference invalidates the pending response.
            Evidence now = knowledge.source(userId,Long.parseLong(ref.userFileId()),
                    ref.generation(),ref.chunkIndex());
            if (now == null || !now.content().equals(previous.evidence().content())) {
                throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
            }
            cited.add(previous);
        }
        return new AnswerResponse(answer,List.copyOf(cited),"MODEL_WITH_CITATIONS");
    }
}
