# 17｜SSE 事件身份与回合截止时间

## 时间戳不是唯一编号

同一毫秒内可以产生两个文本块，也可以先产生最后一块文本，再产生 done。服务端用毫秒时间戳作为 SSE id、前端按 id 去重，就会误删后一个正常事件。

本次 SsePacket 每个包生成独立 UUID，发送使用 eventId，timestamp 只用于观测。同一个包重发保留 ID。消息体的 turnId 和序号也用于客户端去重，兼容仍使用旧时间戳 ID 的服务。

这不是断点续传。当前没有持久事件日志、Last-Event-ID 回放接口或跨进程恢复，文档不能把去重写成可靠重放。

## 原回合 ID 必须随事件传递

旧回合检查自己仍活跃后，新回合可能立即开始。如果发送时再次从 session 读取当前 turnId，旧文字就会被贴上新标签。

现在 Sink 显式传递原 turnId。历史卡片回执也携带原 turnId 和 actionId。done/error 为一次性终态，不重复归档会话记忆。

浏览器还须区分两种 error：携带业务数据的 MessageEvent 不代表 EventSource 的网络传输断开。

## 计时器要覆盖实际等待的阶段

fetch 返回响应头时正文可能尚未到达，此时清掉计时器，res.text() 就可能无限等待。客户端将连接和正文消费放在一个总超时里，同时触发 AbortController。

后端 TurnTaskSupervisor 从任务接收时安排截止时间，覆盖排队、改写、向量化、工具和模型阶段。到期取消 FutureTask 并中断 worker；排队超期任务不会启动。CancellableAgentEventSink 拒绝取消后的业务消息和新卡片。

定时器线程不发送 SSE 网络消息，只改变状态和中断任务；终态通知交给独立有界池，避免慢客户端堵住所有回合的计时器。

## 中断不能撤销外部系统的动作

Java 中断不能强制停止不合作的第三方 I/O，也不能回滚已产生的副作用。实际适配器仍要配置 I/O 超时并提供执行结果核查。未知写结果继续由 ActionGate 锁定，不能因回合已取消而开放重试。

测试不再只断言 isExpired() 为真：TurnTaskSupervisorTest 使用真实阻塞 worker 检查中断与队列取消；前端回归直接发送同时间戳、不同序号的文本和 done，验证两者都保留。
