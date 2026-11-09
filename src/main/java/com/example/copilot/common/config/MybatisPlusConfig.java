package com.example.copilot.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 全局配置：限制分页最大条数，并统一填充审计时间字段。
 *
 * <p>面试要点：分页靠 {@code PaginationInnerInterceptor} 拦截 Mapper 的 {@code selectPage}，
 * 自动改写 SQL 追加 LIMIT 并额外发一条 count 查询；{@code DbType.MYSQL} 指定方言，
 * {@code setMaxLimit(200L)} 是防止「前端传极大 size 拖垮数据库」的兜底，
 * 超限会被静默压到 200。该拦截器必须注册进 {@code MybatisPlusInterceptor} 才会生效。
 *
 * <p>{@code MetaObjectHandler} 在 insert / update 时统一回填 createTime / updateTime，
 * 业务代码无需手写时间，避免各 Service 漏填或时钟不一致；用 {@code strictXxxFill}
 * 仅在字段为 null 且实体声明了填充策略时写入，不覆盖调用方显式赋值。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 插件链，当前只挂 MySQL 分页拦截器。
     * <p>关键规则：maxLimit=200 是单页大小的硬上限，杜绝一次性捞全表；
     * 内部拦截器的加入顺序即执行顺序，分页拦截器通常置于其他内部拦截器之前。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定 MySQL 方言，分页插件据此生成 LIMIT 语法
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最多 200 条，超限时静默收敛，防止大分页打满数据库（与 PageResult 的分页契约呼应）
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 注册审计字段自动填充器，以匿名实现同时处理新增与更新。
     * <p>边界：仅当实体字段用 {@code @TableField(fill=...)} 标注且值为 null 时才填充，
     * 故业务显式设置的时间不会被覆盖；时间取 JVM 当前时钟 {@code LocalDateTime.now()}。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            /**
             * 插入时填充 createTime 与 updateTime。
             * <p>字段名与类型须和实体 {@code @TableField} 声明一致，否则 strictInsertFill 静默跳过。
             */
            @Override
            public void insertFill(MetaObject metaObject) {
                // 新建记录：创建时间与更新时间都取当前时刻
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            /**
             * 更新时仅刷新 updateTime，保留原 createTime 不变。
             * <p>幂等：同一记录多次更新只会不断推进 updateTime，创建时间保持首次写入值。
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
