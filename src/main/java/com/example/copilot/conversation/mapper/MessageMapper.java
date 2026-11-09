package com.example.copilot.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.conversation.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息持久层 Mapper，操作数据库表 cs_message。
 *
 * <p>继承 {@code BaseMapper<Message>}，无自定义 SQL 方法；Service 层用条件构造器完成三类关键查询，
 * 均依赖 conversation_id 过滤与 (conversation_id, id) 联合索引：
 *
 * <ul>
 *   <li>按 conversation_id 过滤、按 id 升序取全量消息（会话消息列表，可叠加 LIMIT 上限）。</li>
 *   <li>按 conversation_id 过滤、按 id 倒序 + LIMIT N 取最近 N 条，再在内存反转为正序，
 *       供 AI 问答拼装「最近 N 条历史消息上下文窗口」。</li>
 *   <li>倒序 LIMIT 1 取最后一条消息，用于会话分页列表的 lastMessage 预览。</li>
 * </ul>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
