import { SseConnectionState, SseEventType } from '../types/chat';

export interface AskAcceptResponse {
  code: number;
  message: string;
  sessionId: string;
  turnId: string;
}

export class SseChatClient {
  private eventSource: EventSource | null = null;
  private sessionId: string = '';
  private connectionState: SseConnectionState = 'disconnected';
  private stateListeners: Set<(state: SseConnectionState) => void> = new Set();
  private readyWaiters: Array<{ resolve: () => void; reject: (err: Error) => void }> = [];
  private seenEventKeys: Set<string> = new Set();

  public getConnectionState(): SseConnectionState {
    return this.connectionState;
  }

  public onConnectionStateChange(listener: (state: SseConnectionState) => void): () => void {
    this.stateListeners.add(listener);
    listener(this.connectionState);
    return () => {
      this.stateListeners.delete(listener);
    };
  }

  private setConnectionState(next: SseConnectionState) {
    this.connectionState = next;
    this.stateListeners.forEach((fn) => {
      try {
        fn(next);
      } catch {
        // ignore listener error
      }
    });
    if (next === 'ready' && this.readyWaiters.length > 0) {
      const waiters = this.readyWaiters.splice(0, this.readyWaiters.length);
      waiters.forEach((w) => w.resolve());
    } else if (next === 'error' && this.readyWaiters.length > 0) {
      const waiters = this.readyWaiters.splice(0, this.readyWaiters.length);
      waiters.forEach((w) => w.reject(new Error('SSE 通道连接异常，无法发送提问')));
    }
  }

  /**
   * 等待 SSE 连接进入 ready 状态 (F08: 发送前连接就绪准入校验)
   */
  public waitForReady(timeoutMs: number = 4000): Promise<void> {
    if (this.connectionState === 'ready' && this.eventSource && this.eventSource.readyState === EventSource.OPEN) {
      return Promise.resolve();
    }
    if (this.connectionState === 'disconnected' || !this.eventSource) {
      return Promise.reject(new Error('SSE 通道尚未建立连接'));
    }
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        const idx = this.readyWaiters.findIndex((w) => w.resolve === wrappedResolve);
        if (idx >= 0) {
          this.readyWaiters.splice(idx, 1);
        }
        reject(new Error('等待 SSE 通道就绪超时，请检查服务连接状态'));
      }, timeoutMs);

      const wrappedResolve = () => {
        clearTimeout(timer);
        resolve();
      };
      const wrappedReject = (err: Error) => {
        clearTimeout(timer);
        reject(err);
      };
      this.readyWaiters.push({ resolve: wrappedResolve, reject: wrappedReject });
    });
  }

  /**
   * 建立长连接监听
   */
  public connect(
    sessionId: string,
    onEvent: (event: SseEventType, data: any) => void,
    onError?: (err: any) => void
  ) {
    this.close();
    this.sessionId = sessionId;
    this.setConnectionState('connecting');

    const url = `/api/v1/chat/connect?sessionId=${encodeURIComponent(sessionId)}`;
    this.eventSource = new EventSource(url);

    this.eventSource.onopen = () => {
      this.setConnectionState('ready');
    };

    // 后端 SseEmitterManager 握手事件
    this.eventSource.addEventListener('connect', () => {
      this.setConnectionState('ready');
    });

    const eventTypes: SseEventType[] = [
      'thinking',
      'message',
      'progress',
      'interactive_card',
      'recommend_questions',
      'conversation_title',
      'done',
      'error',
    ];

    eventTypes.forEach((eventType) => {
      this.eventSource?.addEventListener(eventType, (e: MessageEvent) => {
        this.setConnectionState('ready');
        let parsed: any = e.data;
        try {
          parsed = JSON.parse(e.data);
        } catch {
          parsed = e.data;
        }

        // 事件幂等去重：防止重连或网络重放导致重复渲染同一序号事件 (F07 / T14)
        const dedupKey = this.buildEventDedupKey(eventType, e.lastEventId, parsed);
        if (dedupKey) {
          if (this.seenEventKeys.has(dedupKey)) {
            return;
          }
          this.seenEventKeys.add(dedupKey);
          if (this.seenEventKeys.size > 1000) {
            const firstKey = this.seenEventKeys.values().next().value;
            if (firstKey) {
              this.seenEventKeys.delete(firstKey);
            }
          }
        }

        onEvent(eventType, parsed);
      });
    });

    this.eventSource.onerror = (err) => {
      this.setConnectionState('error');
      if (onError) onError(err);
    };
  }

  private buildEventDedupKey(eventType: SseEventType, lastEventId: string, payload: any): string | null {
    if (lastEventId && lastEventId.trim().length > 0) {
      return `id:${lastEventId.trim()}`;
    }
    if (payload && typeof payload === 'object') {
      const turnId = payload.turnId || '';
      const seq = payload.sequence;
      if (turnId && typeof seq === 'number') {
        return `turn:${turnId}:${eventType}:${seq}`;
      }
      if (eventType === 'done' && turnId) {
        return `turn:${turnId}:done`;
      }
    }
    return null;
  }

  /**
   * 统一 HTTP JSON 请求处理：识别 HTTP 非 2xx、非 JSON 响应（如网关 HTML 错误页）、业务错误码及请求超时 (F08 / T15)
   */
  private async requestJson<T = any>(
    url: string,
    init?: RequestInit,
    timeoutMs: number = 15000,
    enforceBusinessCode: boolean = true
  ): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let res: Response;
    try {
      res = await fetch(url, {
        ...init,
        signal: controller.signal,
      });
    } catch (err: any) {
      if (err?.name === 'AbortError') {
        throw new Error(`请求超时 (${timeoutMs}ms)，请检查网络或服务状态`);
      }
      throw new Error(err?.message || '网络请求失败，无法连接后端服务');
    } finally {
      clearTimeout(timer);
    }

    const contentType = res.headers.get('content-type') || '';
    const rawText = await res.text();

    let parsed: any = null;
    if (rawText && rawText.trim().length > 0) {
      try {
        parsed = JSON.parse(rawText);
      } catch {
        const preview = rawText.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80);
        throw new Error(
          `HTTP ${res.status}: 服务端返回非 JSON 响应 (${contentType || 'unknown'})${preview ? ' - ' + preview : ''}`
        );
      }
    }

    if (!res.ok) {
      const serverMsg = parsed?.message || parsed?.error || `HTTP 请求失败 (状态码 ${res.status})`;
      throw new Error(serverMsg);
    }

    if (enforceBusinessCode && parsed && typeof parsed === 'object' && 'code' in parsed && parsed.code !== 200) {
      throw new Error(parsed.message || `业务请求被拒绝 (code=${parsed.code})`);
    }

    return parsed as T;
  }

  /**
   * 发送用户提问 (携带客户端预生成的 turnId，消除首个 SSE 事件早于 HTTP 响应到达的竞态，F07 / T14)
   */
  public async sendQuestion(
    sessionId: string,
    query: string,
    caseId?: string,
    turnId?: string
  ): Promise<AskAcceptResponse> {
    await this.waitForReady();
    return this.requestJson<AskAcceptResponse>('/api/v1/chat/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId,
        query,
        caseId,
        turnId,
      }),
    });
  }

  /**
   * 用户在通用卡片上点击确认提交
   */
  public async submitCard(
    actionId: string,
    sessionId: string,
    cardType: string,
    formValues: Record<string, any>
  ) {
    return this.requestJson('/api/v1/card/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        actionId,
        sessionId,
        cardType,
        formValues,
      }),
    });
  }

  /**
   * 拉取全量快捷指令面板列表
   */
  public async getCommandPalette() {
    return this.requestJson('/api/v1/commands/palette', { method: 'GET' }, 10000, false);
  }

  /**
   * 断开连接
   */
  public close() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.setConnectionState('disconnected');
  }
}

export const chatClient = new SseChatClient();

