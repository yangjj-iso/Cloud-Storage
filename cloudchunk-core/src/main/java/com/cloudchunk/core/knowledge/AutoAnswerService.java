package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * One-step question -> consented search -> at most four exact, versioned source refs -> grounded answer.
 * No storage tools, no privilege delegation, no automatic upload-time AI calls.
 */
@Service
public class AutoAnswerService {
    private static final int MAX_AUTO_SOURCES = 4;
    private final HybridSearchService retrieval;
    private final GroundedAnswerService answers;

    public AutoAnswerService(HybridSearchService retrieval, GroundedAnswerService answers) {
        this.retrieval = retrieval;
        this.answers = answers;
    }

    public AutoAskResponse ask(long userId, AutoAskRequest request) {
        if (!answers.capabilities().enabled()) throw BizException.of(ErrorCode.AI_DISABLED);
        if (request == null || !request.consent() || request.question() == null ||
                request.question().strip().length() < 4 || request.question().strip().length() > 100) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "请输入4至100字符的问题，并授权将查询及自动选中的资料发送到配置的模型服务");
        }
        String question = request.question().strip();
        // Hybrid search only uses semantic vectors explicitly authorized by this user.
        // When embedding is disabled, it returns the existing literal search results.
        SearchResult results = retrieval.search(userId, question, true);
        List<SourceRef> refs = results.evidence().stream().limit(MAX_AUTO_SOURCES)
                .map(hit -> new SourceRef(hit.userFileId(), hit.generation(), hit.chunkIndex()))
                .distinct().toList();
        if (refs.isEmpty()) {
            return new AutoAskResponse(
                new AnswerResponse("资料不足，无法回答。", List.of(), "INSUFFICIENT_EVIDENCE"),
                results.mode(), 0);
        }
        // GroundedAnswerService itself reloads every pointer via the current user-file ACL,
        // and rechecks ALL input sources after the model finishes.
        AnswerResponse response = answers.answer(userId, new AnswerRequest(question, refs, true));
        return new AutoAskResponse(response, results.mode(), refs.size());
    }
}
