package com.example.copilot.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按模型提供方独立保存的 API Key，对应表 ai_provider_key。
 * <p>在 AI 链路中的位置：与单行表 ai_setting 配合。管理员切换供应商时，
 * 用本表按 provider 取回该供应商已保存的密钥回填 ai_setting.apiKey，
 * 因此从百炼切到 Ollama 再切回百炼时无需重新填写密钥。
 * <p>设计要点：以 provider 字符串作主键（IdType.INPUT），每个提供方一行；
 * apiKey 标注 @JsonIgnore，明文只入库、绝不回传前端。
 */
@Data
@TableName("ai_provider_key")
public class AiProviderKey {

    /** 提供方标识作主键（BAILIAN / OLLAMA），IdType.INPUT 由代码显式指定。 */
    @TableId(type = IdType.INPUT)
    private String provider;

    /** 该提供方的 API Key 明文；@JsonIgnore 保证序列化脱敏，出参只给掩码。 */
    @JsonIgnore
    private String apiKey;

    /** 密钥写入 / 更新时间，FieldFill.INSERT_UPDATE 自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
