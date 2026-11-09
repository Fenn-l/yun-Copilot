package com.example.copilot.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档处理异步线程池。
 * 使用 CallerRunsPolicy：队列满时退回调用线程执行，避免文档任务被静默丢弃。
 *
 * <p>面试要点：文档解析（PDFBox / POI）、约 420 字符切片、Embedding 向量化都是 CPU / IO 密集
 * 且耗时数秒级的操作，放在 HTTP 请求线程会拖垮上传接口；故单独定义名为
 * {@code docExecutor} 的线程池，与 {@code @Async("docExecutor")} 按 Bean 名绑定，
 * 与 Spring 默认的任务执行器隔离，避免互相抢占线程。
 *
 * <p>参数取舍：core=2、max=4 限制并发向量化任务数（Embedding 常受外部模型 QPS 限制），
 * queue=200 削峰；{@code CallerRunsPolicy} 在队列满时让提交线程自己执行，形成天然「背压」——
 * 上传变慢但任务绝不丢失，比 AbortPolicy 直接拒绝更符合「文档不丢」的业务诉求。
 * 线程名前缀 {@code doc-process-} 便于在日志与线程转储中定位慢任务。
 */
@Configuration
public class AsyncConfig {

    /**
     * 定义并初始化文档处理线程池 Bean。
     * <p>关键规则：必须显式调用 {@code initialize()} 才会真正创建底层 ThreadPoolExecutor；
     * 拒绝策略取 CallerRunsPolicy 实现背压而非抛异常；返回 ThreadPoolTaskExecutor 类型，
     * 使 Spring 容器关闭时能优雅停机（等待队列任务排空）。
     * <p>边界：队列有界 200，配合背压避免无界堆积导致 OOM；该池仅供文档链路专用，不复用给普通异步任务。
     */
    @Bean("docExecutor")
    public ThreadPoolTaskExecutor docExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心常驻 2 线程：Embedding 多为外部调用，控制并发避免打满模型服务配额
        executor.setCorePoolSize(2);
        // 最大 4 线程：仅在队列积压时扩容，防止瞬时创建过多线程拖垮 JVM
        executor.setMaxPoolSize(4);
        // 有界队列容量 200：无界队列会掩盖积压并可能 OOM
        executor.setQueueCapacity(200);
        // 线程名前缀，便于 jstack / 日志区分文档处理线程
        executor.setThreadNamePrefix("doc-process-");
        // 队列满则由提交线程自己执行，天然背压且任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 触发底层 ThreadPoolExecutor 初始化，缺此调用核心线程不会预启动
        executor.initialize();
        return executor;
    }
}
