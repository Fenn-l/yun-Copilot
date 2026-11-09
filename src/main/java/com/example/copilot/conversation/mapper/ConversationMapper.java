package com.example.copilot.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.conversation.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话持久层 Mapper，操作数据库表 cs_conversation。
 *
 * <p>继承 MyBatis-Plus 的 {@code BaseMapper<Conversation>}，本接口未声明任何自定义 SQL 方法，
 * 分页、按 id 查询、局部更新等全部由 Service 层用 {@code Wrappers.lambdaQuery()} 条件构造器拼装，
 * 免于编写 XML。泛型绑定 Conversation 实体，由 {@code @TableName} 映射到 cs_conversation 表。
 *
 * <ul>
 *   <li>分页按 updateTime 倒序；普通用户叠加 userId 等值条件，在数据层落地数据级隔离。</li>
 *   <li>close 仅更新 status、updateTitleIfDefault 仅更新 title、appendMessage 仅回写 updateTime，
 *       均通过构造仅含 id 与目标字段的实体调用 updateById，避免覆盖其他列。</li>
 * </ul>
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
