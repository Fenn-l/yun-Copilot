package com.example.copilot.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ai.entity.AiRun;
import com.example.copilot.ai.entity.AiSetting;
import com.example.copilot.ai.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ai_setting 单行配置的 Mapper。
 * <p>自定义 SQL 意图：本表无需复杂查询，运行期统一走 BaseMapper 的
 * selectById(AiSetting.SINGLETON_ID) 读取唯一一行；配置的写入与更新同样由
 * service 层用 BaseMapper 完成（固定主键为 1，先查后写）。
 * <p>约束：单行表语义要求始终只认 SINGLETON_ID，不应按条件查出多行；
 * 配置变更后须由业务层清空网关模型缓存，切换 Embedding 配置后还须重建全部向量索引。
 */
@Mapper
public interface AiSettingMapper extends BaseMapper<AiSetting> {
}
