package com.example.copilot.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.ai.entity.AiProviderKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * ai_provider_key 按提供方密钥的 Mapper。
 * <p>自定义 SQL 意图：以 provider 字符串作主键，用 BaseMapper.selectById(provider)
 * 精确取回某供应商已存的 API Key；新增或轮换密钥用 insert / updateById 完成，无需手写 SQL。
 * <p>约束：切换供应商时由 service 据此回填 ai_setting.apiKey，实现切回免重填；
 * 密钥明文只在本表与数据库之间流转，不进入任何出参。
 */
@Mapper
public interface AiProviderKeyMapper extends BaseMapper<AiProviderKey> {
}
