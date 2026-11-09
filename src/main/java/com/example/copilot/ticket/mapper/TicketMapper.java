package com.example.copilot.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单数据访问接口，主表为 cs_ticket。
 *
 * <p>继承 MyBatis-Plus BaseMapper 获得常规 CRUD 与分页能力；本接口额外定义两类自定义 SQL：
 * 一是带乐观锁的状态流转更新 updateState（工单域并发控制的落地点），二是 dashboard 看板所需的
 * 状态/分类分布与每日趋势聚合查询。所有 SQL 均直接面向 cs_ticket 单表，不涉及跨表关联，
 * 用户昵称等展示信息由 TicketService 回查用户表后组装。
 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 状态流转专用更新，操作表 cs_ticket，是全包乐观锁与并发控制的核心 SQL。
     *
     * <p>SET 部分：写入目标状态 status、处理人 assignee_id、解决时间 resolved_time、
     * 关闭时间 closed_time，并令 version = version + 1、update_time = NOW()。
     * 其中 assigneeId / resolvedTime / closedTime 允许为 null，分别用于「未分配时保持空」
     * 与「reopen 重开时清空解决/关闭时间」等场景。
     *
     * <p>WHERE 三个条件缺一不可，共同保证多人同时操作同一工单时只有一个能成功（CAS）：
     * <ul>
     *   <li>id = #{id}：精确定位目标工单。</li>
     *   <li>version = #{version}：乐观锁核心。仅当数据库当前版本与调用方读到的版本一致才更新，
     *       成功后版本 +1；若期间已被他人改动则版本不匹配，影响行数为 0。</li>
     *   <li>status = #{oldStatus}：把状态机前置条件再下沉到 SQL，确保流转确实从预期的原状态出发，
     *       防止「读到 NEW、落库时已变 IN_PROGRESS」这类脏数据下的越权/错误流转。</li>
     * </ul>
     *
     * <p>返回影响行数。TicketService 在 rows == 0 时抛 409 冲突
     * （「工单已被其他人更新，请刷新后重试」），绝不静默失败，避免状态被并发覆盖。
     */
    @Update("UPDATE cs_ticket SET status = #{newStatus}, assignee_id = #{assigneeId}, " +
            "resolved_time = #{resolvedTime}, closed_time = #{closedTime}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status = #{oldStatus}")
    int updateState(@Param("id") Long id, @Param("version") Integer version,
                    @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus,
                    @Param("assigneeId") Long assigneeId,
                    @Param("resolvedTime") LocalDateTime resolvedTime,
                    @Param("closedTime") LocalDateTime closedTime);

    /** 按状态统计工单数量（GROUP BY status），供 dashboard 看板的状态分布图使用，无额外 WHERE 条件。 */
    @Select("SELECT status, COUNT(*) AS total FROM cs_ticket GROUP BY status")
    List<Map<String, Object>> statusDistribution();

    /** 按分类统计工单数量（GROUP BY category），供 dashboard 看板的分类分布图使用，无额外 WHERE 条件。 */
    @Select("SELECT category, COUNT(*) AS total FROM cs_ticket GROUP BY category")
    List<Map<String, Object>> categoryDistribution();

    /**
     * 按天统计新建工单趋势，供 dashboard 趋势图使用。
     * WHERE create_time >= #{start} 只统计起始时间之后的工单（如近 N 天）；
     * DATE_FORMAT(create_time, '%m-%d') 将创建时间格式化为「月-日」并按其 GROUP BY，
     * ORDER BY day 保证返回序列按日期升序，便于前端直接绘制折线。
     */
    @Select("SELECT DATE_FORMAT(create_time, '%m-%d') AS day, COUNT(*) AS total " +
            "FROM cs_ticket WHERE create_time >= #{start} GROUP BY day ORDER BY day")
    List<Map<String, Object>> dailyTrend(@Param("start") LocalDateTime start);
}
