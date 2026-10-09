package com.example.springai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池隔离配置 (Bulkhead Pattern 舱壁隔离)
 * 严格杜绝全局共享 ForkJoinPool.commonPool()，保障主流程与旁路任务互不抢占资源
 */
@Configuration
@Slf4j
public class ThreadPoolConfig {


    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    /**
     * 1. 智能体流水线专用线程池 (IO 密集型，负责 SSE 长连接与智能体编排调度)
     */
    @Bean("agentPipelineExecutor")
    public Executor agentPipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // IO 密集型经验公式: 核心线程数 = CPU核心数 * 2
        executor.setCorePoolSize(Math.max(4, CPU_CORES * 2));
        // 最大线程数 = CPU核心数 * 4
        executor.setMaxPoolSize(Math.max(8, CPU_CORES * 4));
        // 有界阻塞队列，防止流量激增时无界排队耗尽内存导致 OOM
        executor.setQueueCapacity(500);
        // 线程前缀，极大方便排查日志和 jstack 线程堆栈分析
        executor.setThreadNamePrefix("agent-pipeline-");
        // 拒绝策略: 调用者运行 (CallerRunsPolicy)，形成自然背压，防止系统被冲垮
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机: 容器关闭时等待正在执行的任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        log.info("[ThreadPool] agentPipelineExecutor 初始化完成, core: {}, max: {}, queue: 500",
                executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }

    /**
     * 2. 异步后处理专用线程池 (旁路低优先级任务: 生成推荐问题、总结标题、写审计日志)
     */
    @Bean("agentAsyncPostExecutor")
    public Executor agentAsyncPostExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, CPU_CORES));
        executor.setMaxPoolSize(Math.max(4, CPU_CORES * 2));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-async-post-");
        // 旁路任务若溢出，可直接丢弃老任务或记录日志后丢弃，绝不能影响主流程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);

        executor.initialize();
        log.info("[ThreadPool] agentAsyncPostExecutor 初始化完成, core: {}, max: {}, queue: 200",
                executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }
}
