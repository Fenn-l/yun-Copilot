package com.example.copilot.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.ai.dto.FeedbackRequest;
import com.example.copilot.ai.entity.Feedback;
import com.example.copilot.ai.mapper.FeedbackMapper;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.conversation.entity.Message;
import com.example.copilot.conversation.mapper.MessageMapper;
import com.example.copilot.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 回答反馈服务：处理点赞 / 点踩与文字评论，写入 feedback 表。
 *
 * <p>在 RAG 链路中的位置：用户对 AiChatService 落库的 ASSISTANT 消息做评价，
 * 用于后续答案质量分析与模型、知识库优化，不参与问答主线。
 *
 * <p>关键设计：
 * <ul>
 * <li>只能评价 ASSISTANT 消息，USER 消息或不存在的消息直接拒绝；</li>
 * <li>数据级鉴权：经 requireAccessible 校验消息所属会话对当前用户可见，防止越权评价；</li>
 * <li>幂等：同一用户对同一消息只保留一条反馈，重复提交改为更新 rating / comment（改评）；</li>
 * <li>事务：提交在事务内完成，保证查重与写入一致。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    // feedbackMapper 读写反馈，messageMapper 校验被评价消息，
    // ConversationService 提供会话归属鉴权。
    private final FeedbackMapper feedbackMapper;
    private final MessageMapper messageMapper;
    private final ConversationService conversationService;

    /**
     * 提交或更新一条反馈：校验消息角色与会话归属后，按 messageId + userId 幂等 upsert。
     *
     * <p>边界：非 ASSISTANT 消息或消息不存在抛 BusinessException；
     * 无权访问所属会话由 requireAccessible 拦截；已存在则更新评分与评论，否则插入新记录。
     * 加事务保证查重到更新或插入原子性。
     */
    @Transactional
    public void submit(FeedbackRequest request) {
        // 先查被评价消息，后续角色校验与会话归属都依赖它。
        Message message = messageMapper.selectById(request.getMessageId());
        // 只接受 AI 回答：null 或 USER 角色都拒绝，避免给提问打分。
        if (message == null || !"ASSISTANT".equals(message.getRole())) {
            throw BusinessException.badRequest("只能对 AI 回答进行评价");
        }
        // 数据级鉴权：用消息所属会话做归属校验，防止对他人会话里的 AI 回答越权评价。
        conversationService.requireAccessible(message.getConversationId());
        Long userId = CurrentUser.id();
        // 按 messageId + userId 查已有反馈，实现一人一消息一条的幂等约束。
        Feedback existing = feedbackMapper.selectOne(Wrappers.<Feedback>lambdaQuery()
                .eq(Feedback::getMessageId, request.getMessageId())
                .eq(Feedback::getUserId, userId));
        // 已评价过：只更新评分与评论（支持改评），不重复插入。
        if (existing != null) {
            existing.setRating(request.getRating());
            existing.setComment(request.getComment());
            feedbackMapper.updateById(existing);
            return;
        }
        // 首次评价：落库消息 id、会话 id、用户 id、评分与评论。
        Feedback feedback = new Feedback();
        feedback.setMessageId(request.getMessageId());
        feedback.setConversationId(message.getConversationId());
        feedback.setUserId(userId);
        feedback.setRating(request.getRating());
        feedback.setComment(request.getComment());
        feedbackMapper.insert(feedback);
    }
}
