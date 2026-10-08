import { SseEventType } from '../types/chat';

export class SseChatClient {
  private eventSource: EventSource | null = null;
  private sessionId: string = '';

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

    const url = `/api/v1/chat/connect?sessionId=${encodeURIComponent(sessionId)}`;
    this.eventSource = new EventSource(url);

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
        try {
          const parsed = JSON.parse(e.data);
          onEvent(eventType, parsed);
        } catch {
          onEvent(eventType, e.data);
        }
      });
    });

    this.eventSource.onerror = (err) => {
      if (onError) onError(err);
    };
  }

  /**
   * 发送用户提问
   */
  public async sendQuestion(sessionId: string, query: string, caseId?: string) {
    const res = await fetch('/api/v1/chat/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId,
        query,
        caseId,
      }),
    });
    return res.json();
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
    const res = await fetch('/api/v1/card/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        actionId,
        sessionId,
        cardType,
        formValues,
      }),
    });
    return res.json();
  }

  /**
   * 断开连接
   */
  public close() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}

export const chatClient = new SseChatClient();
