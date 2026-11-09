package com.example.copilot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体，对应数据库表 kb_base，是「知识库 → 文档 → 切片」三级结构的顶层容器。
 *
 * <p>在 RAG 链路中的位置：上传的文档必须归属某个知识库；检索阶段第一步即按
 * visibility 过滤出「当前用户可见的知识库」，再在该范围内召回切片，因此本表是
 * 数据级权限的起点。可见范围判定统一放在 Service 层配合 USER/AGENT/ADMIN 三级角色完成，
 * 实体本身不鉴权。
 *
 * <ul>
 *   <li>visibility 三值：PUBLIC 全员可见 / INTERNAL 客服 AGENT 与管理员 ADMIN 可见 /
 *       PRIVATE 仅创建人 ownerId 本人与管理员 ADMIN 可见。</li>
 *   <li>status 表示知识库启用状态，停用后其下文档不再参与检索。</li>
 *   <li>使用 Lombok @Data 生成访问器、MyBatis-Plus @TableName 映射表名；主键自增，
 *       创建与更新时间由 FieldFill 自动填充。</li>
 * </ul>
 */
@Data
@TableName("kb_base")
public class KnowledgeBase {

    /** 主键 ID，自增（IdType.AUTO）；作为文档表 knowledge_base_id 的外键关联对象。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库名称，列表与详情展示用。 */
    private String name;

    /** 知识库描述，说明用途与收录范围，展示用。 */
    private String description;

    /** 创建人用户 ID，关联用户表；PRIVATE 可见范围判定与创建人鉴权的依据。 */
    private Long ownerId;

    /** 可见范围枚举：PUBLIC 全员 / INTERNAL 客服与管理员 / PRIVATE 创建人与管理员；检索时据此过滤可见知识库。 */
    private String visibility;

    /** 知识库状态，如启用/停用；停用后其下文档不参与 RAG 检索。 */
    private String status;

    /** 创建时间，插入时由 FieldFill.INSERT 自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时由 FieldFill.INSERT_UPDATE 自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
