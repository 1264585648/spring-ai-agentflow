# ☕ Java 高并发与工程底座避坑手册

> 本专栏独立于 AI Agent 架构，专门沉淀 Java 并发编程、线程池治理、JVM 调优、Spring 底层机制与生产故障排查的通用工程实践。

---

## 📑 专栏全景目录树 (持续演进中)

### 一、 并发编程与线程池治理
* [x] 📘 [01. 警惕 ForkJoinPool 饥饿：为什么 CompletableFuture 必须指定专用线程池？](./01-警惕ForkJoinPool饥饿：为什么CompletableFuture必须指定专用线程池.md)
* [ ] ⏳ 02. 线程池核心参数如何科学容量规划？(CPU密集型 vs IO密集型)
* [ ] ⏳ 03. 生产环境如何防范 ThreadPoolTaskExecutor 队列导致的 OOM？
* [ ] ⏳ 04. 优雅停机 (Graceful Shutdown)：如何避免部署重启时丢异步任务？

### 二、 架构模式与高并发读写分离
* [x] 📘 [02. 高性能内存读写分离与零停机热重载：AtomicReference 与 Spring 事件驱动实战](./02-高性能内存读写分离与零停机热重载：AtomicReference与Spring事件驱动实战.md)
* [ ] ⏳ 05. Spring 事件驱动模型 (ApplicationEventPublisher) 的异步与事务陷阱
* [ ] ⏳ 06. 为什么高并发下优先使用 ConcurrentHashMap 与 LongAdder？
