# ☕ Java 高并发与工程底座避坑手册

> 本专栏独立于 AI Agent 架构，专门沉淀 Java 并发编程、线程池治理、JVM 调优、Spring 底层机制与生产故障排查的通用工程实践。

---

## 📑 专栏全景目录树 (持续演进中)

### 一、 并发编程与线程池治理
* [x] 📘 [01. 警惕 ForkJoinPool 陷阱：为什么 CompletableFuture 必须指定隔离线程池？](./01-警惕ForkJoinPool陷阱：为什么CompletableFuture必须指定隔离线程池.md)
* [ ] ⏳ 02. 线程池核心参数如何科学容量规划？(CPU密集型 vs IO密集型)
* [ ] ⏳ 03. 生产环境如何防范 ThreadPoolTaskExecutor 队列导致的 OOM？
* [ ] ⏳ 04. 优雅停机 (Graceful Shutdown)：如何避免部署重启时丢异步任务？

### 二、 Spring 底层与性能调优
* [ ] ⏳ 05. Spring 事件驱动模型 (ApplicationEventPublisher) 的异步陷阱
* [ ] ⏳ 06. 为什么高并发下优先使用 ConcurrentHashMap 与 LongAdder？
