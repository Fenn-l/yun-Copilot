package com.example.copilot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.copilot.system.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper，操作 cs_user 表，是 system 包访问用户数据的唯一持久层入口。
 *
 * <p>继承 MyBatis-Plus 的 {@code BaseMapper<User>} 后即获得 insert / selectById / selectOne /
 * selectList / selectCount / selectPage / updateById 等单表 CRUD，无需编写 XML。
 * 本接口当前没有自定义方法：注册判重、按用户名查询、客服列表、分页与启停等需求
 * 均以 Wrappers.lambdaQuery() 条件构造器在 Service 层拼装，SQL 意图由方法名与条件表达式体现，
 * 从而避免手写 SQL、降低注入风险，并保持查询字段与实体属性的编译期绑定。
 *
 * <p>{@code @Mapper} 让 Spring 扫描并生成代理实现；认证链路（SecurityUserDetailsService、
 * JwtAuthFilter）与管理链路（UserService）共用此 Mapper，保证用户数据源唯一。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
