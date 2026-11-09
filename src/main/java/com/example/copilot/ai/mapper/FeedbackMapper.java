package com.example.copilot.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ai.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * cs_feedback 用户评价的 Mapper。
 * <p>自定义 SQL 意图：评价写入用 BaseMapper.insert；按 messageId 查询某条回答的评价、
 * 按会话或时间窗统计好评率等，都由 service 层用条件构造器完成，无需手写 SQL。
 * <p>约束：rating 仅 0 / 1 两种取值，统计好评率时按该口径过滤。
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
