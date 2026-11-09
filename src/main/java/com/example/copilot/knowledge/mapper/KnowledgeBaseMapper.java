package com.example.copilot.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库表 kb_base 的 MyBatis-Plus Mapper，继承 BaseMapper 获得单表 CRUD 能力。
 *
 * <p>在 RAG 链路中位于持久层入口：Service 层通过本接口按 visibility 条件查询
 * 「当前用户可见的知识库」，作为检索过滤与数据级鉴权的第一道边界；
 * 创建、改名、停用等写操作同样经此落库。
 *
 * <p>本接口为空扩展、未声明自定义 SQL：带 visibility / owner_id 条件的查询由
 * LambdaQueryWrapper 条件构造器完成，避免把角色相关的权限条件硬编码进 SQL，
 * 也便于 USER/AGENT/ADMIN 三级角色在 Service 层统一收口。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
