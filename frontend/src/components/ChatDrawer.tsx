import React, { useState, useEffect, useRef } from 'react';
import { ChatMessage, SseEventType, CommandPaletteItem } from '../types/chat';
import { chatClient } from '../api/sseClient';
import { ThinkingPanel } from './ThinkingPanel';
import { InteractiveCard } from './InteractiveCard';
import { QuestionChips } from './QuestionChips';
import { CommandPalette } from './CommandPalette';
import { MessageSquareText, Send, X, RefreshCw, Bot, User, ShieldAlert, Zap } from 'lucide-react';

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

  // 斜杠快捷指令面板状态
  const [paletteCommands, setPaletteCommands] = useState<CommandPaletteItem[]>([]);
  const [showPalette, setShowPalette] = useState<boolean>(false);
  const [paletteSelectedIndex, setPaletteSelectedIndex] = useState<number>(0);

  const sessionIdRef = useRef<string>('sess_' + Date.now());
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
            <div style={{ fontSize: '15px', fontWeight: 600 }}>企业级智能客服与工单协同助手</div>
            <div style={{ fontSize: '12px', color: '#94a3b8' }}>
              当前关联服务单：<span style={{ color: '#38bdf8' }}>{caseId}</span>
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
              marginTop: '40px',
              fontSize: '13px',
              lineHeight: '1.8',
            }}
          >
            <ShieldAlert size={36} color="#38bdf8" style={{ margin: '0 auto 10px' }} />
            <div style={{ fontWeight: 600, color: '#334155', fontSize: '14px' }}>智能客服与工单协同助手已就绪</div>
            <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '16px' }}>
              点击下方快捷指令或输入问题直接体验：
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', maxWidth: '340px', margin: '0 auto' }}>
              <button
                onClick={() => handleSendMessage('#ping')}
                style={{
                  padding: '8px 12px',
                  backgroundColor: '#ffffff',
                  border: '1px solid #e2e8f0',
                  borderRadius: '6px',
                  fontSize: '12px',
                  color: '#2563eb',
                  cursor: 'pointer',
                  textAlign: 'left',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  boxShadow: '0 1px 2px rgba(0,0,0,0.03)'
                }}
              >
                <span>⚡</span> <strong>#ping</strong> <span style={{ color: '#64748b', fontSize: '11px' }}>(L1规则直通探活 &lt;5ms)</span>
              </button>
              <button
                onClick={() => handleSendMessage('/help')}
                style={{
                  padding: '8px 12px',
                  backgroundColor: '#ffffff',
                  border: '1px solid #e2e8f0',
                  borderRadius: '6px',
                  fontSize: '12px',
                  color: '#2563eb',
                  cursor: 'pointer',
                  textAlign: 'left',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  boxShadow: '0 1px 2px rgba(0,0,0,0.03)'
                }}
              >
                <span>📌</span> <strong>/help</strong> <span style={{ color: '#64748b', fontSize: '11px' }}>(L1指令帮助菜单直出)</span>
              </button>
              <button
                onClick={() => handleSendMessage('/query user_id=10001')}
                style={{
                  padding: '8px 12px',
                  backgroundColor: '#ffffff',
                  border: '1px solid #e2e8f0',
                  borderRadius: '6px',
                  fontSize: '12px',
                  color: '#2563eb',
                  cursor: 'pointer',
                  textAlign: 'left',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  boxShadow: '0 1px 2px rgba(0,0,0,0.03)'
                }}
              >
                <span>🛠️</span> <strong>/query user_id=10001</strong> <span style={{ color: '#64748b', fontSize: '11px' }}>(L1反射调度Tool查询账户)</span>
              </button>
              <button
                onClick={() => handleSendMessage('客户反馈因不可抗力突发特殊情况，申请服务争议费用折让与补偿，符合什么政策？')}
                style={{
                  padding: '8px 12px',
                  backgroundColor: '#ffffff',
                  border: '1px solid #e2e8f0',
                  borderRadius: '6px',
                  fontSize: '12px',
                  color: '#0f766e',
                  cursor: 'pointer',
                  textAlign: 'left',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  boxShadow: '0 1px 2px rgba(0,0,0,0.03)'
                }}
              >
                <span>💬</span> <strong>服务特批与费用争议政策咨询</strong> <span style={{ color: '#64748b', fontSize: '11px' }}>(复杂自然语言流式问答)</span>
              </button>
            </div>
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
            placeholder={isStreaming ? 'AI 正在分析回复中...' : '输入 / 或 # 唤起指令，或输入诉求...'}
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
