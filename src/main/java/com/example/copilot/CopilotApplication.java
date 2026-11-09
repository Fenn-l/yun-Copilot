package com.example.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动入口。
 * 开启异步线程池（文档解析/向量化）与定时任务能力，其余组件由 Spring Boot 自动装配。
 *
 * <p>面试要点：{@code @SpringBootApplication} 等价于 {@code @Configuration} +
 * {@code @EnableAutoConfiguration} + {@code @ComponentScan}，以本类所在包
 * {@code com.example.copilot} 为扫描根，故 system / conversation / knowledge / ai /
 * ticket / copilot / dashboard / seed / common 等子包必须位于其下才会被装配。
 *
 * <p>{@code @EnableAsync} 使 {@code @Async("docExecutor")} 生效，把 PDF/Word 解析、
 * 切片与 Embedding 向量化等秒级耗时操作移出 HTTP 请求线程；{@code @EnableScheduling}
 * 支撑定时任务。入口只做容器引导，不承载任何业务逻辑。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class CopilotApplication {

    /**
     * 启动 Spring 容器：创建应用上下文、执行自动装配与组件扫描、拉起内嵌 Web 服务器。
     * <p>args 透传命令行参数（如 {@code --server.port}、profile 等）；正常情况阻塞至容器关闭，
     * 无返回值、不抛受检异常；启动失败由 Spring Boot 打印失败分析报告后退出进程。
     */
    public static void main(String[] args) {
        SpringApplication.run(CopilotApplication.class, args);
    }
}
