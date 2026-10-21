import { SseConnectionState, SseEventType } from '../types/chat';

export interface AskAcceptResponse { code: number; message: string; sessionId: string; turnId: string; }
export class CardSubmissionError extends Error {
  constructor(message: string, public readonly retryable: boolean, public readonly errorType: string) {
    super(message); this.name = 'CardSubmissionError';
  }
}
class ApiRequestError extends Error {
  constructor(message: string, readonly status: number, readonly body: any) { super(message); }
}
export class SseChatClient {
  private eventSource: EventSource | null = null;
  private sessionId = '';
  private connectionState: SseConnectionState = 'disconnected';
  private stateListeners = new Set<(state: SseConnectionState) => void>();
  private readyWaiters = new Set<{ resolve: () => void; reject: (err: Error) => void }>();
  private seenEventKeys = new Set<string>();
  constructor(private readonly requestTimeoutMs = 15000) {}
  public getConnectionState() { return this.connectionState; }
  public onConnectionStateChange(listener: (state: SseConnectionState) => void) {
    this.stateListeners.add(listener); listener(this.connectionState);
    return () => { this.stateListeners.delete(listener); };
  }
  private setConnectionState(next: SseConnectionState) {
    this.connectionState = next;
    this.stateListeners.forEach(fn => { try { fn(next); } catch { /* observer isolation */ } });
    if (next === 'ready') [...this.readyWaiters].forEach(w => w.resolve());
    if (next === 'error' || next === 'disconnected') {
      [...this.readyWaiters].forEach(w => w.reject(new Error('SSE 通道未就绪或已断开')));
    }
  }
  public waitForReady(timeoutMs = 4000): Promise<void> {
    if (this.connectionState === 'ready' && this.eventSource?.readyState === EventSource.OPEN) return Promise.resolve();
    if (!this.eventSource || this.connectionState === 'disconnected') return Promise.reject(new Error('SSE 通道尚未建立'));
    return new Promise((resolve, reject) => {
      const waiter = {
        resolve: () => { clearTimeout(timer); this.readyWaiters.delete(waiter); resolve(); },
        reject: (err: Error) => { clearTimeout(timer); this.readyWaiters.delete(waiter); reject(err); },
      };
      const timer = setTimeout(() => waiter.reject(new Error('等待 SSE 通道就绪超时')), timeoutMs);
      this.readyWaiters.add(waiter);
    });
  }
  public connect(sessionId: string, onEvent: (event: SseEventType, data: any) => void, onError?: (err: any) => void) {
    this.close();
    if (this.sessionId !== sessionId) this.seenEventKeys.clear();
    this.sessionId = sessionId;
    this.setConnectionState('connecting');
    const source = new EventSource(`/api/v1/chat/connect?sessionId=${encodeURIComponent(sessionId)}`);
    this.eventSource = source;
    source.onopen = () => { if (this.eventSource === source) this.setConnectionState('ready'); };
    source.addEventListener('connect', () => { if (this.eventSource === source) this.setConnectionState('ready'); });
    const types: SseEventType[] = ['thinking', 'message', 'progress', 'interactive_card', 'recommend_questions', 'conversation_title', 'done', 'error'];
    types.forEach(type => source.addEventListener(type, (event: MessageEvent) => {
      if (this.eventSource !== source || typeof event.data !== 'string') return;
      let data: any;
      try { data = JSON.parse(event.data); } catch { data = event.data; }
      this.setConnectionState('ready');
      const key = this.buildEventDedupKey(type, event.lastEventId, data);
      if (key && this.seenEventKeys.has(key)) return;
      if (key) {
        this.seenEventKeys.add(key);
        if (this.seenEventKeys.size > 1000) this.seenEventKeys.delete(this.seenEventKeys.values().next().value!);
      }
      onEvent(type, data);
    }));
    source.onerror = event => {
      // 同名的业务 error 是 MessageEvent，不代表 SSE 传输断开。
      if (this.eventSource !== source || typeof (event as MessageEvent).data === 'string') return;
      this.setConnectionState('error'); onError?.(event);
    };
  }
  private buildEventDedupKey(type: SseEventType, id: string, data: any): string | null {
    // 兼容服务滚动升级：旧服务的毫秒 ID 可能重复，优先使用协议序列。
    if (data?.turnId && Number.isSafeInteger(data.sequence)) return `turn:${data.turnId}:${type}:${data.sequence}`;
    if (id?.trim()) return `id:${id.trim()}`;
    return type === 'done' && data?.turnId ? `turn:${data.turnId}:done` : null;
  }
  private async requestJson<T = any>(url: string, init?: RequestInit, timeoutMs = this.requestTimeoutMs, enforceBusinessCode = true): Promise<T> {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        controller.abort(); reject(new Error(`请求超时 (${timeoutMs}ms)`));
      }, timeoutMs);
    });
    const operation = async (): Promise<T> => {
      const res = await fetch(url, { ...init, signal: controller.signal });
      // 正文消费也在同一个总超时内；即使自定义 fetch 忽略 AbortSignal，调用方仍及时退出。
      const raw = await res.text();
      let body: any;
      try { body = JSON.parse(raw); }
      catch { throw new Error(`HTTP ${res.status}: 服务端返回非 JSON 响应`); }
      if (!res.ok || (enforceBusinessCode && body && 'code' in Object(body) && body.code !== 200)) {
        throw new ApiRequestError(typeof body?.message === 'string' ? body.message : `HTTP ${res.status}`, res.status, body);
      }
      return body as T;
    };
    try { return await Promise.race([operation(), deadline]); }
    finally { clearTimeout(timer); }
  }
  public async sendQuestion(sessionId: string, query: string, caseId?: string, turnId?: string): Promise<AskAcceptResponse> {
    await this.waitForReady();
    return this.requestJson('/api/v1/chat/ask', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId, query, caseId, turnId }),
    });
  }
  public async submitCard(actionId: string, sessionId: string, cardType: string, formValues: Record<string, any>) {
    try {
      const body = await this.requestJson('/api/v1/card/submit', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ actionId, sessionId, cardType, formValues }),
      });
      if (body?.code !== 200 || typeof body.ticketId !== 'string' || !body.ticketId.trim()) throw new Error('不完整的执行回执');
      return body;
    } catch (error) {
      if (error instanceof ApiRequestError) {
        throw new CardSubmissionError(error.message,
          error.status === 400 && error.body?.errorType === 'INVALID_SUBMISSION' && error.body?.retryable === true,
          typeof error.body?.errorType === 'string' ? error.body.errorType : 'SUBMISSION_ERROR');
      }
      throw new CardSubmissionError('提交回执未确认，请先核查动作状态；不要重复提交或重新发起相同操作', false, 'RESPONSE_UNKNOWN');
    }
  }
  public async cancelCard(actionId: string, sessionId: string, reason = '用户主动放弃') {
    return this.requestJson('/api/v1/card/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ actionId, sessionId, reason }),
    });
  }
  public getCommandPalette() { return this.requestJson('/api/v1/commands/palette', { method: 'GET' }, 10000, false); }
  public close() {
    const old = this.eventSource; this.eventSource = null; old?.close();
    this.setConnectionState('disconnected');
  }
}
export const chatClient = new SseChatClient();
