package com.example.copilot.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ai.entity.AiRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * cs_ai_run 调用记录的 Mapper，运营看板的数据访问入口。
 * <p>自定义 SQL 意图：在 BaseMapper 之上补充 4 个聚合查询，全部以 create_time >= start
 * 限定时间窗，返回 List/Map 或标量，字段直接对齐前端图表，避免把原始流水拉到内存再算。
 * <p>面试关注点：用 CASE WHEN 在库内算成功数、用 AVG/SUM 聚合耗时与 Token、
 * 用 IFNULL 兜底空结果集，避免无数据时返回 null 让看板报错。
 */
@Mapper
public interface AiRunMapper extends BaseMapper<AiRun> {

    /**
     * 按天统计调用总量与成功数，供趋势图使用。
     * <p>SQL 意图：DATE_FORMAT(create_time, '%m-%d') 归一到天，COUNT(*) 为总量，
     * SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) 为成功数，按天分组排序；
     * 成功率计算留给前端，后端只给原始计数，口径更灵活。
     *
     * @param start 统计起始时间（含），只统计该时间窗内的记录
     * @return 每天的 day / total / success 列表
     */
    @Select("SELECT DATE_FORMAT(create_time, '%m-%d') AS day, COUNT(*) AS total, " +
            "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success " +
            "FROM cs_ai_run WHERE create_time >= #{start} GROUP BY day ORDER BY day")
    List<Map<String, Object>> dailyTrend(@Param("start") LocalDateTime start);

    /**
     * 按场景统计调用量、平均耗时与 Token 消耗，供场景分布看板使用。
     * <p>SQL 意图：GROUP BY scene，COUNT(*) 为调用量、ROUND(AVG(latency_ms)) 为平均耗时、
     * SUM(prompt_tokens + completion_tokens) 为 Token 总量，用于对比 CHAT / SUMMARY /
     * CLASSIFY / REPLY 各场景的用量与成本。
     *
     * @param start 统计起始时间（含）
     * @return 每个 scene 的 total / avg_latency / tokens
     */
    @Select("SELECT scene, COUNT(*) AS total, " +
            "ROUND(AVG(latency_ms)) AS avg_latency, " +
            "SUM(prompt_tokens + completion_tokens) AS tokens " +
            "FROM cs_ai_run WHERE create_time >= #{start} GROUP BY scene")
    List<Map<String, Object>> sceneStats(@Param("start") LocalDateTime start);

    /**
     * 统计时间窗内平均调用耗时，用于首页概览卡片。
     * <p>SQL 意图：IFNULL(AVG(latency_ms), 0) 在窗口内无记录时返回 0 而非 null，
     * 避免看板拿到 null 做数值展示时出错。
     *
     * @param start 统计起始时间（含）
     * @return 平均耗时（毫秒），无数据为 0
     */
    @Select("SELECT IFNULL(AVG(latency_ms), 0) FROM cs_ai_run WHERE create_time >= #{start}")
    Double avgLatencySince(@Param("start") LocalDateTime start);

    /**
     * 统计时间窗内 Token 总消耗，用于成本估算概览。
     * <p>SQL 意图：SUM(prompt_tokens + completion_tokens) 把输入与输出 Token 合并计数，
     * IFNULL 兜底空结果集为 0。
     *
     * @param start 统计起始时间（含）
     * @return Token 总量，无数据为 0
     */
    @Select("SELECT IFNULL(SUM(prompt_tokens + completion_tokens), 0) FROM cs_ai_run WHERE create_time >= #{start}")
    Long tokenSumSince(@Param("start") LocalDateTime start);
}
