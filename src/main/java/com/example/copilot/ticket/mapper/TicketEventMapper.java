package com.example.copilot.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ticket.entity.TicketEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单事件流水访问接口，主表为 cs_ticket_event。
 *
 * <p>继承 MyBatis-Plus BaseMapper 已足够覆盖本域全部需求：插入事件（TicketService#addEvent
 * 调用 insert）、按 ticket_id 查询并升序排列时间线（TicketService#detail 调用 selectList）。
 * 事件属于只追加（append-only）的审计流水，一旦写入不修改、不删除，故接口内不定义任何
 * 自定义 @Update / @Delete SQL，也不提供定制更新方法。
 */
@Mapper
public interface TicketEventMapper extends BaseMapper<TicketEvent> {
}
