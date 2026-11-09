package com.example.copilot.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.ai.entity.AiRun;
import com.example.copilot.ai.mapper.AiRunMapper;
import com.example.copilot.conversation.entity.Conversation;
import com.example.copilot.conversation.mapper.ConversationMapper;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import com.example.copilot.knowledge.mapper.KbChunkMapper;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.mapper.KnowledgeBaseMapper;
import com.example.copilot.ticket.entity.Ticket;
import com.example.copilot.ticket.mapper.TicketMapper;
import com.example.copilot.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营看板数据聚合服务，为 StatsController 的 /overview 接口一次性组装全部指标。
 * <p>
 * 在业务链路中只做只读聚合：数据源横跨工单、会话、知识库、切片与 AI 调用埋点
 * （cs_ai_run）多个 Mapper，不写任何业务表。
 * <ul>
 *   <li>聚合口径：计数用 MyBatis-Plus 条件构造器（Wrappers），状态/分类分布、按日趋势、
 *       AI 场景统计、平均耗时与 Token 汇总则下沉到 Mapper 自定义 SQL（GROUP BY / 按日分组），
 *       避免把明细拉回内存再计算；时间窗分「今日 0 点起」与「近 7 日 0 点起」两套。</li>
 *   <li>空数据处理：AI 调用数为 0 时成功率返回 null 以避免除零（前端可显示无数据），
 *       平均耗时为 null 时归 0；分布/趋势在无数据时由 SQL 侧返回空集合。</li>
 *   <li>返回 LinkedHashMap 而非 HashMap，保证前端拿到字段顺序稳定、便于按序渲染。</li>
 *   <li>最近工单经 ticketService.toVO 转成 VO 返回，避免把实体内部字段直接暴露给前端。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 工单数据源：总工单数、未结/待分配计数、今日解决/关闭数、状态与分类分布、每日趋势、最近工单。 */
    private final TicketMapper ticketMapper;
    /** 会话数据源：会话总数与 ACTIVE 活跃会话数。 */
    private final ConversationMapper conversationMapper;
    /** 知识库数据源：统计 ACTIVE 状态的有效知识库数量。 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /** 文档数据源：统计已上传知识文档总数。 */
    private final KbDocumentMapper documentMapper;
    /** 切片数据源：统计向量切片总数（反映知识库向量化规模）。 */
    private final KbChunkMapper chunkMapper;
    /** AI 调用埋点数据源：当日调用数/成功数、平均耗时、Token 汇总、每日趋势与场景分布。 */
    private final AiRunMapper aiRunMapper;
    /** 工单服务：复用其 toVO 转换，把最近工单实体脱敏为前端 VO。 */
    private final TicketService ticketService;

    /**
     * 一次性组装看板全部指标并以扁平 Map 返回，字段顺序由 LinkedHashMap 固定，便于前端按序渲染。
     * <p>
     * 聚合口径：
     * <ul>
     *   <li>时间窗：startOfDay 为今日 0 点（当日计数 / AI 指标），trendStart 为 6 天前 0 点
     *       （近 7 日趋势，含今日共 7 天）。</li>
     *   <li>「未结工单」与「待分配」均以状态白名单 NEW / ASSIGNED / IN_PROGRESS / WAITING_USER
     *       界定（六态状态机中尚未 RESOLVED / CLOSED 的在途状态），待分配再叠加 assigneeId IS NULL。</li>
     *   <li>resolvedToday / closedToday 按 resolvedTime / closedTime 大于等于今日 0 点统计，
     *       与「当前所处状态」是不同的统计维度。</li>
     *   <li>AI 指标只取 cs_ai_run 中 createTime 在今日之后的记录；成功率 = SUCCESS 数 / 总数并保留 1 位小数，
     *       平均耗时、Token 汇总、按日趋势与场景分布由 Mapper 自定义聚合 SQL 完成。</li>
     *   <li>statusDistribution / categoryDistribution / dailyTrend / sceneStats 均为 SQL 侧
     *       GROUP BY 与按日分组口径，避免把明细拉回 JVM 内存再分组。</li>
     * </ul>
     * 空数据兜底：runsToday 为 0 时 aiSuccessRate 返回 null（防除零，由前端显示「无数据」而非 0%），
     * avgLatency 为 null 时归 0，各类计数与分布天然为 0 / 空集合；最近工单取最新创建的 5 条
     * 并经 toVO 转换后再返回，避免暴露实体内部字段。
     * @return 看板指标 Map
     */
    public Map<String, Object> overview() {
        // 时间双口径：今日 0 点用于当日指标；trendStart 为近 7 日趋势起点（今日往前 6 天，含今日共 7 天）
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime trendStart = LocalDate.now().minusDays(6).atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTickets", ticketMapper.selectCount(null));
        // 在途状态白名单：NEW/ASSIGNED/IN_PROGRESS/WAITING_USER，对应六态状态机中未 RESOLVED/CLOSED 的单据
        result.put("openTickets", ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .in(Ticket::getStatus, List.of("NEW", "ASSIGNED", "IN_PROGRESS", "WAITING_USER"))));
        // 待分配：在上述在途状态白名单基础上，再叠加 assigneeId IS NULL
        result.put("unassignedTickets", ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .in(Ticket::getStatus, List.of("NEW", "ASSIGNED", "IN_PROGRESS", "WAITING_USER"))
                .isNull(Ticket::getAssigneeId)));
        result.put("resolvedToday", ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .ge(Ticket::getResolvedTime, startOfDay)));
        result.put("closedToday", ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .ge(Ticket::getClosedTime, startOfDay)));

        result.put("totalConversations", conversationMapper.selectCount(null));
        result.put("activeConversations", conversationMapper.selectCount(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getStatus, "ACTIVE")));

        result.put("knowledgeBases", knowledgeBaseMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getStatus, "ACTIVE")));
        result.put("documents", documentMapper.selectCount(null));
        result.put("chunks", chunkMapper.selectCount(null));

        long runsToday = aiRunMapper.selectCount(Wrappers.<AiRun>lambdaQuery()
                .ge(AiRun::getCreateTime, startOfDay));
        long successToday = aiRunMapper.selectCount(Wrappers.<AiRun>lambdaQuery()
                .ge(AiRun::getCreateTime, startOfDay)
                .eq(AiRun::getStatus, "SUCCESS"));
        result.put("aiRunsToday", runsToday);
        // 空数据防除零：当日无 AI 调用时成功率返回 null，交前端显示「无数据」，而非误导性的 0%
        result.put("aiSuccessRate", runsToday == 0 ? null
                : Math.round(successToday * 1000.0 / runsToday) / 10.0);
        Double avg = aiRunMapper.avgLatencySince(startOfDay);
        // avgLatencySince 在无记录时返回 null，这里归 0，保证前端数值字段类型稳定
        result.put("aiAvgLatency", avg == null ? 0 : Math.round(avg));
        result.put("aiTokensToday", aiRunMapper.tokenSumSince(startOfDay));

        // 以下分布 / 趋势 / 场景统计走 Mapper 自定义 GROUP BY 与按日分组 SQL，聚合口径下沉到数据库
        result.put("statusDistribution", ticketMapper.statusDistribution());
        result.put("categoryDistribution", ticketMapper.categoryDistribution());
        result.put("ticketTrend", ticketMapper.dailyTrend(trendStart));
        result.put("aiTrend", aiRunMapper.dailyTrend(trendStart));
        result.put("sceneStats", aiRunMapper.sceneStats(trendStart));

        // 最近 5 条工单按创建时间倒序，LIMIT 下推到 SQL；经 toVO 转换后再返回，避免暴露实体内部字段
        List<Ticket> recent = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .orderByDesc(Ticket::getCreateTime)
                .last("LIMIT 5"));
        result.put("recentTickets", recent.stream().map(ticketService::toVO).toList());
        return result;
    }
}
