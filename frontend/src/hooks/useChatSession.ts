import { useCallback, useEffect, useRef, useState } from 'react';
import { chatClient } from '../api/sseClient';
import { cancelTurn } from '../api/turnCancellation';
import { ChatMessage, InteractiveCardData, SseConnectionState, SseEventType } from '../types/chat';

interface LocalTurn { id: string; stopped: boolean; }
const newId = (prefix: string) => `${prefix}_${crypto.randomUUID()}`;
const ownsCard = (message: ChatMessage, actionId: string) =>
  message.interactiveCard?.actionId === actionId || message.interactiveCards?.some(card => card.actionId === actionId);

/** 回合生命周期独立于抽屉可见性；每个异步请求仅能结束自己的 LocalTurn。 */
export function useChatSession(isOpen: boolean, caseId: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [connectionState, setConnectionState] = useState<SseConnectionState>('disconnected');
  const [currentProgress, setCurrentProgress] = useState<string | null>(null);
  const sessionIdRef = useRef<string>();
  if (!sessionIdRef.current) sessionIdRef.current = newId('sess');
  const sessionId = sessionIdRef.current;
  const activeTurnRef = useRef<LocalTurn | null>(null);

  const requestCancellation = useCallback((turnId: string) => {
    void cancelTurn(sessionId, turnId).catch(error => {
      // 本地停止不能伪装成后台取消成功；也不能自动重试卡片写操作。
      console.warn('[Turn cancellation not confirmed]', error);
    });
  }, [sessionId]);

  const updateMessage = useCallback((turnId: string | undefined, actionId: string | undefined,
    updater: (message: ChatMessage) => ChatMessage, allowTerminal = false, receiptOnly = false) => {
    setMessages(previous => {
      let index = actionId ? previous.findIndex(message => message.role === 'assistant' && ownsCard(message, actionId)) : -1;
      if (index < 0 && !receiptOnly && turnId) {
        index = previous.findIndex(message => message.role === 'assistant' && message.turnId === turnId);
      }
      if (index < 0 || (previous[index].turnDone && !allowTerminal)) return previous;
      const next = [...previous];
      next[index] = updater(next[index]);
      return next;
    });
  }, []);

  const stopLocalTurn = useCallback((reason: string) => {
    const turn = activeTurnRef.current;
    if (!turn) return;
    turn.stopped = true;
    activeTurnRef.current = null;
    setIsStreaming(false);
    setCurrentProgress(null);
    updateMessage(turn.id, undefined, message => ({ ...message, turnDone: true, errorText: reason }));
    requestCancellation(turn.id);
  }, [requestCancellation, updateMessage]);

  const onEvent = useCallback((type: SseEventType, data: any) => {
    const active = activeTurnRef.current;
    // 无 turnId 的兼容包只能归到接收瞬间的活动回合，绝不回退到已结束的最后一条消息。
    const turnId = typeof data?.turnId === 'string' ? data.turnId : active?.id;
    const actionId = typeof data?.actionId === 'string' ? data.actionId : undefined;
    const isCurrent = !!active && turnId === active.id;
    const isReceipt = type === 'message' && data?.cardReceipt === true && !!actionId;
    if (isReceipt) {
      updateMessage(turnId, actionId, message => ({ ...message, content: message.content + (data?.content || '') }), true, true);
      return;
    }
    if (!isCurrent) return;
    switch (type) {
      case 'thinking':
        updateMessage(turnId, undefined, message => ({ ...message, thinkingContent: (message.thinkingContent || '') + (data?.content || '') }));
        break;
      case 'progress':
        setCurrentProgress(data?.description || '正在处理中...');
        break;
      case 'message':
        setCurrentProgress(null);
        updateMessage(turnId, undefined, message => ({ ...message, content: message.content + (data?.content || '') }));
        break;
      case 'interactive_card':
        updateMessage(turnId, undefined, message => {
          const card = data as InteractiveCardData;
          const cards = [...(message.interactiveCards || [])];
          const index = cards.findIndex(existing => existing.actionId === card.actionId);
          if (index < 0) cards.push(card); else cards[index] = card;
          return { ...message, interactiveCard: card, interactiveCards: cards };
        });
        break;
      case 'recommend_questions':
        updateMessage(turnId, undefined, message => ({ ...message, recommendQuestions: data?.questions || [] }));
        break;
      case 'done':
      case 'error':
        activeTurnRef.current = null;
        setIsStreaming(false);
        setCurrentProgress(null);
        updateMessage(turnId, undefined, message => ({
          ...message, turnDone: true,
          errorText: type === 'error' ? (typeof data === 'string' ? data : data?.message || '处理失败') : message.errorText,
        }));
        break;
    }
  }, [updateMessage]);

  useEffect(() => {
    const unsubscribe = chatClient.onConnectionStateChange(setConnectionState);
    if (isOpen) {
      chatClient.connect(sessionId, onEvent, () => {
        stopLocalTurn('实时连接中断，本地回合已停止；后台取消结果未确认，请先核查。');
      });
    }
    return () => {
      if (isOpen) stopLocalTurn('抽屉已关闭，本地回合已停止；后台取消结果未确认，请先核查。');
      unsubscribe();
      chatClient.close();
    };
  }, [isOpen, sessionId, onEvent, stopLocalTurn]);

  const sendMessage = useCallback(async (query: string): Promise<boolean> => {
    const text = query.trim();
    // 同步引用阻止同一 React 批处理内的双击，不能只依靠尚未提交的 isStreaming state。
    if (!isOpen || !text || activeTurnRef.current) return false;
    const turn: LocalTurn = { id: newId('turn'), stopped: false };
    const originalTurnId = turn.id;
    activeTurnRef.current = turn;
    const now = Date.now();
    setMessages(previous => [...previous,
      { id: newId('msg'), turnId: turn.id, role: 'user', content: text, timestamp: now },
      { id: newId('msg_asst'), turnId: turn.id, role: 'assistant', content: '', thinkingContent: '', interactiveCards: [], retryQuery: text, timestamp: now },
    ]);
    setIsStreaming(true);
    setCurrentProgress('正在连接智能体流水线...');
    try {
      const response = await chatClient.sendQuestion(sessionId, text, caseId, originalTurnId);
      if (turn.stopped) {
        // 关闭时的取消可能先于 ask 被接收；受理回执迟到后再按原 turnId 尝试取消。
        requestCancellation(originalTurnId);
        return true;
      }
      if (activeTurnRef.current === turn && response?.turnId && response.turnId !== turn.id) {
        const previousId = turn.id;
        turn.id = response.turnId;
        setMessages(previous => previous.map(message => message.turnId === previousId ? { ...message, turnId: turn.id } : message));
      }
    } catch (error) {
      if (turn.stopped) return true;
      if (activeTurnRef.current === turn) {
        activeTurnRef.current = null;
        turn.stopped = true;
        setIsStreaming(false);
        setCurrentProgress(null);
        requestCancellation(turn.id);
      }
      // SSE 已经确认 done 的历史消息不因 HTTP 迟到失败而被改为失败。
      updateMessage(turn.id, undefined, message => ({ ...message, turnDone: true,
        errorText: error instanceof Error ? error.message : '提问请求发送失败' }));
    }
    return true;
  }, [isOpen, caseId, sessionId, requestCancellation, updateMessage]);

  const submitCard = useCallback(async (actionId: string, cardType: string, formValues: Record<string, any>) => {
    const response = await chatClient.submitCard(actionId, sessionId, cardType, formValues);
    setMessages(previous => previous.map(message => {
      if (!ownsCard(message, actionId)) return message;
      const single = message.interactiveCard?.actionId === actionId;
      return { ...message, cardSubmitted: single ? true : message.cardSubmitted,
        ticketId: single ? response.ticketId : message.ticketId,
        submittedCardMap: { ...(message.submittedCardMap || {}), [actionId]: { submitted: true, ticketId: response.ticketId } },
      };
    }));
    return response.ticketId as string;
  }, [sessionId]);

  return { messages, isStreaming, connectionState, currentProgress, sendMessage, submitCard };
}
