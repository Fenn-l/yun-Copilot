package com.example.copilot.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库视图对象，用于知识库列表、详情查询接口的响应体，对应 kb_base 表的对外呈现。
 *
 * <p>它不是实体的一比一映射：ownerName、documentCount、chunkCount 属于跨表关联/聚合字段，
 * 需要 Service 层关联用户表、统计 kb_document 与 kb_chunk 后组装，BaseMapper 单表查询取不到；
 * @Builder 便于 Service 层一次性构造。对外只暴露展示所需字段，不泄露内部存储路径等细节。
 *
 * <p>visibility 决定前端能否看到该库，其合法值与 Service 层数据级鉴权一致：
 * PUBLIC 全员 / INTERNAL 客服与管理员 / PRIVATE 创建人与管理员。
 */
@Data
@Builder
public class KnowledgeBaseVO {
    /** 知识库主键 ID，对应 kb_base.id。 */
    private Long id;
    /** 知识库名称。 */
    private String name;
    /** 知识库描述。 */
    private String description;
    /** 创建人用户 ID，对应 kb_base.owner_id。 */
    private Long ownerId;
    /** 创建人显示名称，关联用户表得到的冗余展示字段。 */
    private String ownerName;
    /** 可见范围枚举：PUBLIC 全员 / INTERNAL 客服与管理员 / PRIVATE 创建人与管理员。 */
    private String visibility;
    /** 知识库状态，停用后其下文档不参与检索。 */
    private String status;
    /** 该知识库下的文档数量，聚合统计所得，用于列表展示。 */
    private long documentCount;
    /** 该知识库下的切片数量，聚合统计所得，反映索引规模。 */
    private long chunkCount;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
