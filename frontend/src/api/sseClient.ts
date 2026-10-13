import { SseEventType } from '../types/chat';

/** 只有服务端明确确认尚未执行且允许修正时，才允许再次提交。 */
export class CardSubmissionError extends Error {
  constructor(message: string, public readonly retryable: boolean, public readonly errorType: string) {
    super(message);
    this.name = 'CardSubmissionError';
  }
}

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

  /** 用户确认。网络断开/回执损坏不等于后端没有执行，不自动建议重试。 */
  public async submitCard(
    actionId: string,
    sessionId: string,
    cardType: string,
    formValues: Record<string, any>
  ) {
    try {
      const res = await fetch('/api/v1/card/submit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ actionId, sessionId, cardType, formValues }),
      });
      const body = await res.json();
      if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
        throw new Error('Invalid card submission response');
      }
      if (!res.ok || body.code !== 200) {
        throw new CardSubmissionError(
          typeof body.message === 'string' ? body.message : '提交未完成，请核查动作状态',
          res.status === 400 && body.retryable === true,
          typeof body.errorType === 'string' ? body.errorType : 'SUBMISSION_ERROR'
        );
      }
      return body;
    } catch (error) {
      if (error instanceof CardSubmissionError) throw error;
      throw new CardSubmissionError(
        '提交回执未确认，请先核查动作状态；不要重复提交或重新发起相同操作',
        false,
        'RESPONSE_UNKNOWN'
      );
    }
  }

  /**
   * 拉取全量快捷指令面板列表
   */
  public async getCommandPalette() {
    const res = await fetch('/api/v1/commands/palette');
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
