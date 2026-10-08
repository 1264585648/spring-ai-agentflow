import React, { useState, useEffect, useRef } from 'react';
import { ChatMessage, SseEventType } from '../types/chat';
import { chatClient } from '../api/sseClient';
import { ThinkingPanel } from './ThinkingPanel';
import { InteractiveCard } from './InteractiveCard';
import { QuestionChips } from './QuestionChips';
import { MessageSquareText, Send, X, RefreshCw, Bot, User, ShieldAlert } from 'lucide-react';

interface ChatDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  caseId?: string;
}

export const ChatDrawer: React.FC<ChatDrawerProps> = ({ isOpen, onClose, caseId = 'CASE_10086' }) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputQuery, setInputQuery] = useState<string>('');
  const [isStreaming, setIsStreaming] = useState<boolean>(false);
  const [currentProgress, setCurrentProgress] = useState<string | null>(null);

  const sessionIdRef = useRef<string>('sess_' + Date.now());
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, currentProgress]);

  // 初始化长连接
  useEffect(() => {
    if (isOpen) {
      chatClient.connect(
        sessionIdRef.current,
        (eventType: SseEventType, data: any) => {
          handleIncomingSseEvent(eventType, data);
        },
        (err) => {
          console.error('[SSE Error]', err);
          setIsStreaming(false);
        }
      );
    }
    return () => {
      chatClient.close();
    };
  }, [isOpen]);

  const handleIncomingSseEvent = (eventType: SseEventType, data: any) => {
    switch (eventType) {
      case 'thinking':
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === 'assistant') {
            return [
              ...prev.slice(0, -1),
              {
                ...last,
                thinkingContent: (last.thinkingContent || '') + (data.content || ''),
              },
            ];
          }
          return prev;
        });
        break;

      case 'progress':
        setCurrentProgress(`${data.description || '正在处理中...'}`);
        break;

      case 'message':
        setCurrentProgress(null);
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === 'assistant') {
            return [
              ...prev.slice(0, -1),
              {
                ...last,
                content: (last.content || '') + (data.content || ''),
              },
            ];
          }
          return prev;
        });
        break;

      case 'interactive_card':
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === 'assistant') {
            return [
              ...prev.slice(0, -1),
              {
                ...last,
                interactiveCard: data,
              },
            ];
          }
          return prev;
        });
        break;

      case 'recommend_questions':
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === 'assistant') {
            return [
              ...prev.slice(0, -1),
              {
                ...last,
                recommendQuestions: data.questions || [],
              },
            ];
          }
          return prev;
        });
        break;

      case 'done':
        setIsStreaming(false);
        setCurrentProgress(null);
        break;

      case 'error':
        setIsStreaming(false);
        setCurrentProgress(null);
        break;
    }
  };

  const handleSendMessage = async (textToSend?: string) => {
    const text = textToSend || inputQuery;
    if (!text.trim() || isStreaming) return;

    const userMsg: ChatMessage = {
      id: 'msg_' + Date.now(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    };

    const assistantMsg: ChatMessage = {
      id: 'msg_asst_' + Date.now(),
      role: 'assistant',
      content: '',
      thinkingContent: '',
      timestamp: Date.now(),
    };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInputQuery('');
    setIsStreaming(true);
    setCurrentProgress('正在连接智能体流水线...');

    try {
      await chatClient.sendQuestion(sessionIdRef.current, text, caseId);
    } catch (e: any) {
      setIsStreaming(false);
      setCurrentProgress(null);
      alert('提问失败：' + e.message);
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
        prev.map((m) =>
          m.interactiveCard?.actionId === actionId
            ? { ...m, cardSubmitted: true, ticketId: res.ticketId }
            : m
        )
      );
      return res.ticketId;
    } else {
      throw new Error(res.message || '方案提交处理失败');
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
      <div
        style={{
          padding: '14px 18px',
          borderBottom: '1px solid #e2e8f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          backgroundColor: '#0f172a',
          color: '#ffffff',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Bot size={20} color="#38bdf8" />
          <div>
            <div style={{ fontSize: '15px', fontWeight: 600 }}>催收智能答疑与BPM工单助手</div>
            <div style={{ fontSize: '12px', color: '#94a3b8' }}>
              当前绑定案件：<span style={{ color: '#38bdf8' }}>{caseId}</span>
            </div>
          </div>
        </div>
        <button
          onClick={onClose}
          style={{
            background: 'none',
            border: 'none',
            color: '#94a3b8',
            cursor: 'pointer',
            padding: '4px',
          }}
        >
          <X size={18} />
        </button>
      </div>

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
          <div
            style={{
              textAlign: 'center',
              color: '#94a3b8',
              marginTop: '60px',
              fontSize: '13px',
              lineHeight: '1.8',
            }}
          >
            <ShieldAlert size={36} color="#cbd5e1" style={{ margin: '0 auto 10px' }} />
            <div>催收合规知识与方案测算助手已就绪</div>
            <div>您可以咨询抗辩话术、息费减免政策或直接提报停催工单</div>
          </div>
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

              {/* 核心卡片：通用人机协同方案确认卡片 */}
              {msg.role === 'assistant' && msg.interactiveCard && (
                <InteractiveCard
                  card={msg.interactiveCard}
                  submitted={msg.cardSubmitted}
                  ticketId={msg.ticketId}
                  onSubmit={handleCardSubmit}
                />
              )}

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
        }}
      >
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
          <input
            type="text"
            placeholder={isStreaming ? 'AI 正在分析回复中...' : '输入催收疑问，或粘贴客户诉求...'}
            disabled={isStreaming}
            value={inputQuery}
            onChange={(e) => setInputQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSendMessage()}
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
            disabled={isStreaming || !inputQuery.trim()}
            style={{
              backgroundColor: isStreaming || !inputQuery.trim() ? '#cbd5e1' : '#2563eb',
              color: '#ffffff',
              border: 'none',
              borderRadius: '6px',
              padding: '6px 12px',
              cursor: isStreaming || !inputQuery.trim() ? 'not-allowed' : 'pointer',
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
