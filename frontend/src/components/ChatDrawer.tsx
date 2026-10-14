import React, { useState, useEffect, useRef } from 'react';
import {
  ChatMessage,
  SseEventType,
  SseConnectionState,
  CommandPaletteItem,
  InteractiveCardData,
} from '../types/chat';
import { chatClient } from '../api/sseClient';
import { ThinkingPanel } from './ThinkingPanel';
import { InteractiveCard } from './InteractiveCard';
import { QuestionChips } from './QuestionChips';
import { CommandPalette } from './CommandPalette';
import { ChatHeader } from './ChatHeader';
import { WelcomeGuide } from './WelcomeGuide';
import { MessageSquareText, Send, X, RefreshCw, Bot, User, Zap } from 'lucide-react';

interface ChatDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  caseId?: string;
}

export const ChatDrawer: React.FC<ChatDrawerProps> = ({ isOpen, onClose, caseId = 'CASE_10086' }) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputQuery, setInputQuery] = useState<string>('');
  const [isStreaming, setIsStreaming] = useState<boolean>(false);
  const [connectionState, setConnectionState] = useState<SseConnectionState>('disconnected');
  const [currentProgress, setCurrentProgress] = useState<string | null>(null);

  // 斜杠快捷指令面板状态
  const [paletteCommands, setPaletteCommands] = useState<CommandPaletteItem[]>([]);
  const [showPalette, setShowPalette] = useState<boolean>(false);
  const [paletteSelectedIndex, setPaletteSelectedIndex] = useState<number>(0);

  const sessionIdRef = useRef<string>('sess_' + Date.now());
  const activeTurnIdRef = useRef<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // 初始化加载全量可用快捷指令清单
  useEffect(() => {
    chatClient
      .getCommandPalette()
      .then((cmds) => {
        if (Array.isArray(cmds)) {
          setPaletteCommands(cmds);
        }
      })
      .catch((err) => console.error('[CommandPalette] 加载失败:', err));
  }, []);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, currentProgress]);

  // 订阅长连接状态与事件流
  useEffect(() => {
    const unsubscribeState = chatClient.onConnectionStateChange((st) => {
      setConnectionState(st);
    });

    if (isOpen) {
      chatClient.connect(
        sessionIdRef.current,
        (eventType: SseEventType, data: any) => {
          handleIncomingSseEvent(eventType, data);
        },
        (err) => {
          console.error('[SSE Transport Error]', err);
          if (activeTurnIdRef.current) {
            const interruptedTurnId = activeTurnIdRef.current;
            activeTurnIdRef.current = null;
            setIsStreaming(false);
            setCurrentProgress(null);
            setMessages((prev) =>
              updateTargetAssistantMessage(prev, interruptedTurnId, undefined, (msg) => ({
                ...msg,
                turnDone: true,
                errorText: msg.errorText || 'SSE 实时连接中断，当前回合已终止，请点击重试。',
              }))
            );
          }
        }
      );
    }
    return () => {
      unsubscribeState();
      chatClient.close();
    };
  }, [isOpen]);

  /**
   * 根据 actionId 或 turnId 精确定位目标助手消息，防止旧回合或旧卡片回执串入新回合 (F07 / T13 / T14)
   */
  const updateTargetAssistantMessage = (
    list: ChatMessage[],
    eventTurnId: string | undefined,
    actionId: string | undefined,
    updater: (msg: ChatMessage) => ChatMessage
  ): ChatMessage[] => {
    let targetIndex = -1;

    // 1. 若携带 actionId（如卡片执行回执），优先匹配持有该卡片的原回合消息
    if (actionId) {
      targetIndex = list.findIndex(
        (m) =>
          m.role === 'assistant' &&
          (m.interactiveCard?.actionId === actionId ||
            (m.interactiveCards && m.interactiveCards.some((c) => c.actionId === actionId)))
      );
    }

    // 2. 按事件携带的 turnId 精确匹配对应回合
    if (targetIndex < 0 && eventTurnId) {
      targetIndex = list.findIndex((m) => m.role === 'assistant' && m.turnId === eventTurnId);
    }

    // 3. 兼容未携带 turnId 的旧事件：仅当当前有活动 turnId 时回退匹配活动回合
    if (targetIndex < 0 && !eventTurnId && activeTurnIdRef.current) {
      targetIndex = list.findIndex((m) => m.role === 'assistant' && m.turnId === activeTurnIdRef.current);
    }

    // 4. 兜底匹配最后一条未结束的助手消息
    if (targetIndex < 0 && !eventTurnId && !actionId) {
      for (let i = list.length - 1; i >= 0; i--) {
        if (list[i].role === 'assistant') {
          targetIndex = i;
          break;
        }
      }
    }

    if (targetIndex < 0) {
      return list;
    }

    const next = [...list];
    next[targetIndex] = updater(next[targetIndex]);
    return next;
  };

  const handleIncomingSseEvent = (eventType: SseEventType, data: any) => {
    const eventTurnId: string | undefined = typeof data === 'object' && data !== null ? data.turnId : undefined;
    const eventActionId: string | undefined = typeof data === 'object' && data !== null ? data.actionId : undefined;

    switch (eventType) {
      case 'thinking':
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, eventActionId, (msg) => ({
            ...msg,
            thinkingContent: (msg.thinkingContent || '') + (data?.content || ''),
          }))
        );
        break;

      case 'progress':
        if (!eventTurnId || !activeTurnIdRef.current || eventTurnId === activeTurnIdRef.current) {
          setCurrentProgress(`${data?.description || '正在处理中...'}`);
        }
        break;

      case 'message':
        if (!eventTurnId || !activeTurnIdRef.current || eventTurnId === activeTurnIdRef.current) {
          setCurrentProgress(null);
        }
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, eventActionId, (msg) => ({
            ...msg,
            content: (msg.content || '') + (data?.content || ''),
          }))
        );
        break;

      case 'interactive_card':
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, data?.actionId, (msg) => {
            const incomingCard: InteractiveCardData = data;
            const existingCards = msg.interactiveCards ? [...msg.interactiveCards] : [];
            const dupIndex = existingCards.findIndex((c) => c.actionId === incomingCard.actionId);
            if (dupIndex >= 0) {
              existingCards[dupIndex] = incomingCard;
            } else {
              existingCards.push(incomingCard);
            }
            return {
              ...msg,
              interactiveCard: incomingCard,
              interactiveCards: existingCards,
            };
          })
        );
        break;

      case 'recommend_questions':
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, eventActionId, (msg) => ({
            ...msg,
            recommendQuestions: data?.questions || [],
          }))
        );
        break;

      case 'done':
        if (!eventTurnId || !activeTurnIdRef.current || eventTurnId === activeTurnIdRef.current) {
          activeTurnIdRef.current = null;
          setIsStreaming(false);
          setCurrentProgress(null);
        }
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, eventActionId, (msg) => ({
            ...msg,
            turnDone: true,
          }))
        );
        break;

      case 'error': {
        const text = typeof data === 'string' ? data : data?.message || '处理失败';
        if (!eventTurnId || !activeTurnIdRef.current || eventTurnId === activeTurnIdRef.current) {
          activeTurnIdRef.current = null;
          setIsStreaming(false);
          setCurrentProgress(null);
        }
        setMessages((prev) =>
          updateTargetAssistantMessage(prev, eventTurnId, eventActionId, (msg) => ({
            ...msg,
            turnDone: true,
            errorText: text,
          }))
        );
        break;
      }
    }
  };

  const handleSendMessage = async (textToSend?: string) => {
    const text = (textToSend ?? inputQuery).trim();
    if (!text || isStreaming) return;

    // 预生成本回合唯一标识 turnId，消除首个 SSE 事件早于 HTTP 响应到达的竞态 (F07 / T14)
    const turnId = 'turn_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
    activeTurnIdRef.current = turnId;

    const now = Date.now();
    const userMsg: ChatMessage = {
      id: 'msg_' + now,
      turnId,
      role: 'user',
      content: text,
      timestamp: now,
    };

    const assistantMsg: ChatMessage = {
      id: 'msg_asst_' + now,
      turnId,
      role: 'assistant',
      content: '',
      thinkingContent: '',
      interactiveCards: [],
      retryQuery: text,
      timestamp: now,
    };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInputQuery('');
    setIsStreaming(true);
    setCurrentProgress('正在连接智能体流水线...');

    try {
      const acceptRes = await chatClient.sendQuestion(sessionIdRef.current, text, caseId, turnId);
      if (acceptRes?.turnId && acceptRes.turnId !== turnId) {
        activeTurnIdRef.current = acceptRes.turnId;
        setMessages((prev) =>
          prev.map((m) => (m.turnId === turnId ? { ...m, turnId: acceptRes.turnId } : m))
        );
      }
    } catch (e: any) {
      if (activeTurnIdRef.current === turnId) {
        activeTurnIdRef.current = null;
      }
      setIsStreaming(false);
      setCurrentProgress(null);
      const errMsg = e?.message || '提问请求发送失败';
      setMessages((prev) =>
        updateTargetAssistantMessage(prev, turnId, undefined, (msg) => ({
          ...msg,
          turnDone: true,
          errorText: errMsg,
          retryQuery: text,
        }))
      );
    }
  };

  const handleCardSubmit = async (actionId: string, cardType: string, formValues: Record<string, any>) => {
    const res = await chatClient.submitCard(
      actionId,
      sessionIdRef.current,
      cardType,
      formValues
    );

    if (res.code === 200) {
      setMessages((prev) =>
        prev.map((m) => {
          const matchesSingle = m.interactiveCard?.actionId === actionId;
          const matchesList = m.interactiveCards?.some((c) => c.actionId === actionId);
          if (!matchesSingle && !matchesList) {
            return m;
          }
          const nextMap = {
            ...(m.submittedCardMap || {}),
            [actionId]: { submitted: true, ticketId: res.ticketId },
          };
          return {
            ...m,
            cardSubmitted: matchesSingle ? true : m.cardSubmitted,
            ticketId: matchesSingle ? res.ticketId : m.ticketId,
            submittedCardMap: nextMap,
          };
        })
      );
      return res.ticketId;
    } else {
      throw new Error(res.message || '方案提交处理失败');
    }
  };

  // 根据当前输入实时过滤快捷指令
  const filteredCommands = paletteCommands.filter((cmd) => {
    const query = inputQuery.trim().toLowerCase();
    if (!query || query === '/' || query === '#') return true;
    const cleanQuery = query.startsWith('/') || query.startsWith('#') ? query.slice(1) : query;
    return (
      cmd.prefix.toLowerCase().includes(query) ||
      cmd.name.toLowerCase().includes(cleanQuery) ||
      cmd.code.toLowerCase().includes(cleanQuery) ||
      (cmd.description && cmd.description.toLowerCase().includes(cleanQuery))
    );
  });

  const handleInputChange = (val: string) => {
    setInputQuery(val);
    if (val.startsWith('/') || val.startsWith('#')) {
      setShowPalette(true);
      setPaletteSelectedIndex(0);
    } else if (showPalette && !val.trim()) {
      setShowPalette(false);
    }
  };

  const togglePalette = () => {
    if (!showPalette) {
      if (!inputQuery.startsWith('/') && !inputQuery.startsWith('#')) {
        setInputQuery('/');
      }
      setShowPalette(true);
      setPaletteSelectedIndex(0);
      setTimeout(() => inputRef.current?.focus(), 50);
    } else {
      setShowPalette(false);
    }
  };

  const handleSelectCommand = (cmd: CommandPaletteItem) => {
    const template = cmd.template || cmd.prefix;
    setInputQuery(template);
    setShowPalette(false);
    setTimeout(() => {
      inputRef.current?.focus();
    }, 50);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (showPalette && filteredCommands.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setPaletteSelectedIndex((prev) => (prev + 1) % filteredCommands.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setPaletteSelectedIndex((prev) => (prev - 1 + filteredCommands.length) % filteredCommands.length);
        return;
      }
      if (e.key === 'Tab') {
        e.preventDefault();
        if (filteredCommands[paletteSelectedIndex]) {
          handleSelectCommand(filteredCommands[paletteSelectedIndex]);
        }
        return;
      }
      if (e.key === 'Enter') {
        // 如果输入未含空格（未输完参数），Enter 则触发指令自动补全
        if (!inputQuery.includes(' ') && filteredCommands[paletteSelectedIndex]) {
          e.preventDefault();
          handleSelectCommand(filteredCommands[paletteSelectedIndex]);
          return;
        }
        setShowPalette(false);
        handleSendMessage();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setShowPalette(false);
        return;
      }
    }

    if (e.key === 'Enter') {
      handleSendMessage();
    }
  };

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: 'fixed',
        right: 0,
        top: 0,
        width: '460px',
        height: '100vh',
        backgroundColor: '#ffffff',
        boxShadow: '-4px 0 20px rgba(0,0,0,0.12)',
        display: 'flex',
        flexDirection: 'column',
        zIndex: 9999,
        borderLeft: '1px solid #e2e8f0',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      }}
    >
      {/* 顶部标题栏 */}
      <ChatHeader caseId={caseId} onClose={onClose} />

      {/* 消息历史滚动区 */}
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '16px',
          display: 'flex',
          flexDirection: 'column',
          gap: '16px',
          backgroundColor: '#f8fafc',
        }}
      >
        {messages.length === 0 && (
          <WelcomeGuide onSelectPrompt={handleSendMessage} />
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            style={{
              display: 'flex',
              flexDirection: msg.role === 'user' ? 'row-reverse' : 'row',
              gap: '10px',
              alignItems: 'flex-start',
            }}
          >
            <div
              style={{
                width: '32px',
                height: '32px',
                borderRadius: '50%',
                backgroundColor: msg.role === 'user' ? '#3b82f6' : '#0f172a',
                color: '#ffffff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
            >
              {msg.role === 'user' ? <User size={16} /> : <Bot size={16} color="#38bdf8" />}
            </div>

            <div style={{ maxWidth: '85%' }}>
              {/* 助手消息专属：思考折叠面板 */}
              {msg.role === 'assistant' && msg.thinkingContent && (
                <ThinkingPanel content={msg.thinkingContent} isStreaming={isStreaming} />
              )}

              {/* 正文气泡 */}
              {msg.content && (
                <div
                  style={{
                    padding: '10px 14px',
                    borderRadius: '10px',
                    fontSize: '13px',
                    lineHeight: '1.6',
                    whiteSpace: 'pre-wrap',
                    backgroundColor: msg.role === 'user' ? '#2563eb' : '#ffffff',
                    color: msg.role === 'user' ? '#ffffff' : '#1e293b',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
                    border: msg.role === 'user' ? 'none' : '1px solid #e2e8f0',
                  }}
                >
                  {msg.content}
                </div>
              )}

              {/* 回合错误终态与一键重试入口 (F08 / T15) */}
              {msg.role === 'assistant' && msg.errorText && (
                <div
                  style={{
                    marginTop: msg.content ? '8px' : '0',
                    padding: '10px 12px',
                    borderRadius: '8px',
                    fontSize: '12px',
                    lineHeight: '1.5',
                    backgroundColor: '#fef2f2',
                    color: '#991b1b',
                    border: '1px solid #fecaca',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '6px',
                  }}
                >
                  <div>⚠️ {msg.errorText}</div>
                  {msg.retryQuery && !isStreaming && (
                    <button
                      type="button"
                      onClick={() => handleSendMessage(msg.retryQuery)}
                      style={{
                        alignSelf: 'flex-start',
                        padding: '4px 10px',
                        fontSize: '12px',
                        borderRadius: '6px',
                        border: '1px solid #f87171',
                        backgroundColor: '#ffffff',
                        color: '#b91c1c',
                        cursor: 'pointer',
                      }}
                    >
                      重试该提问
                    </button>
                  )}
                </div>
              )}

              {/* 核心卡片：支持同回合多张交互卡片按 actionId 独立渲染与提交状态管理 (F10 / T17) */}
              {msg.role === 'assistant' &&
                ((msg.interactiveCards && msg.interactiveCards.length > 0)
                  ? msg.interactiveCards
                  : msg.interactiveCard
                    ? [msg.interactiveCard]
                    : []
                ).map((cardItem) => {
                  const cardState = msg.submittedCardMap?.[cardItem.actionId];
                  const isSubmitted = cardState
                    ? cardState.submitted
                    : msg.interactiveCard?.actionId === cardItem.actionId
                      ? msg.cardSubmitted
                      : false;
                  const resolvedTicketId = cardState
                    ? cardState.ticketId
                    : msg.interactiveCard?.actionId === cardItem.actionId
                      ? msg.ticketId
                      : undefined;
                  return (
                    <InteractiveCard
                      key={cardItem.actionId}
                      card={cardItem}
                      submitted={isSubmitted}
                      ticketId={resolvedTicketId}
                      onSubmit={handleCardSubmit}
                    />
                  );
                })}

              {/* 推荐追问气泡 */}
              {msg.role === 'assistant' && msg.recommendQuestions && (
                <QuestionChips
                  questions={msg.recommendQuestions}
                  onSelect={(q) => handleSendMessage(q)}
                />
              )}
            </div>
          </div>
        ))}

        {/* 过程进度提示 */}
        {currentProgress && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontSize: '12px',
              color: '#64748b',
              padding: '6px 12px',
              backgroundColor: '#e2e8f0',
              borderRadius: '6px',
              alignSelf: 'flex-start',
            }}
          >
            <RefreshCw size={12} className="spin-animate" />
            <span>{currentProgress}</span>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* 底部输入框 */}
      <div
        style={{
          padding: '14px 16px',
          borderTop: '1px solid #e2e8f0',
          backgroundColor: '#ffffff',
          position: 'relative',
        }}
      >
        {/* 斜杠快捷指令浮层面板 */}
        {showPalette && (
          <CommandPalette
            commands={filteredCommands}
            selectedIndex={paletteSelectedIndex}
            onSelect={handleSelectCommand}
          />
        )}

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            backgroundColor: '#f1f5f9',
            borderRadius: '8px',
            padding: '6px 10px',
          }}
        >
          {/* 快捷指令触发按钮 */}
          <button
            type="button"
            onClick={togglePalette}
            title="快捷指令面板 (输入 / 或 # 唤起)"
            style={{
              background: showPalette ? '#dbeafe' : 'transparent',
              border: 'none',
              borderRadius: '6px',
              padding: '4px 6px',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: showPalette ? '#2563eb' : '#64748b',
              transition: 'background-color 0.15s, color 0.15s',
            }}
          >
            <Zap size={16} />
          </button>

          <input
            ref={inputRef}
            type="text"
            placeholder={
              isStreaming
                ? 'AI 正在分析回复中...'
                : connectionState === 'connecting'
                  ? '正在建立实时通道...'
                  : '输入 / 或 # 唤起指令，或输入诉求...'
            }
            disabled={isStreaming}
            value={inputQuery}
            onChange={(e) => handleInputChange(e.target.value)}
            onKeyDown={handleKeyDown}
            style={{
              flex: 1,
              border: 'none',
              outline: 'none',
              backgroundColor: 'transparent',
              fontSize: '13px',
              color: '#1e293b',
            }}
          />
          <button
            onClick={() => handleSendMessage()}
            disabled={isStreaming || !inputQuery.trim() || connectionState === 'connecting'}
            style={{
              backgroundColor:
                isStreaming || !inputQuery.trim() || connectionState === 'connecting'
                  ? '#cbd5e1'
                  : '#2563eb',
              color: '#ffffff',
              border: 'none',
              borderRadius: '6px',
              padding: '6px 12px',
              cursor:
                isStreaming || !inputQuery.trim() || connectionState === 'connecting'
                  ? 'not-allowed'
                  : 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
              fontSize: '12px',
            }}
          >
            <Send size={13} />
            发送
          </button>
        </div>
      </div>
    </div>
  );
};

