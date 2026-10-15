import React, { useState, useEffect, useRef } from 'react';
import { CommandPaletteItem } from '../types/chat';
import { chatClient } from '../api/sseClient';
import { useChatSession } from '../hooks/useChatSession';
import { ThinkingPanel } from './ThinkingPanel';
import { InteractiveCard } from './InteractiveCard';
import { QuestionChips } from './QuestionChips';
import { CommandPalette } from './CommandPalette';
import { ChatHeader } from './ChatHeader';
import { WelcomeGuide } from './WelcomeGuide';
import { Send, RefreshCw, Bot, User, Zap } from 'lucide-react';

interface ChatDrawerProps { isOpen: boolean; onClose: () => void; caseId?: string; }

export const ChatDrawer: React.FC<ChatDrawerProps> = ({ isOpen, onClose, caseId = 'CASE_10086' }) => {
  const { messages, isStreaming, connectionState, currentProgress, sendMessage, submitCard } = useChatSession(isOpen, caseId);
  const [inputQuery, setInputQuery] = useState('');
  const [paletteCommands, setPaletteCommands] = useState<CommandPaletteItem[]>([]);
  const [showPalette, setShowPalette] = useState(false);
  const [paletteSelectedIndex, setPaletteSelectedIndex] = useState(0);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    chatClient.getCommandPalette().then(commands => {
      if (Array.isArray(commands)) setPaletteCommands(commands);
    }).catch(error => console.error('[CommandPalette] 加载失败:', error));
  }, []);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, currentProgress]);

  const handleSendMessage = (textToSend?: string) => {
    const text = (textToSend ?? inputQuery).trim();
    if (!text || isStreaming || !isOpen) return;
    setInputQuery('');
    void sendMessage(text);
  };
  const filteredCommands = paletteCommands.filter(command => {
    const query = inputQuery.trim().toLowerCase();
    if (!query || query === '/' || query === '#') return true;
    const clean = query.startsWith('/') || query.startsWith('#') ? query.slice(1) : query;
    return command.prefix.toLowerCase().includes(query) || command.name.toLowerCase().includes(clean)
      || command.code.toLowerCase().includes(clean) || command.description?.toLowerCase().includes(clean);
  });
  const handleInputChange = (value: string) => {
    setInputQuery(value);
    if (value.startsWith('/') || value.startsWith('#')) { setShowPalette(true); setPaletteSelectedIndex(0); }
    else if (showPalette && !value.trim()) setShowPalette(false);
  };
  const togglePalette = () => {
    if (!showPalette) {
      if (!inputQuery.startsWith('/') && !inputQuery.startsWith('#')) setInputQuery('/');
      setShowPalette(true); setPaletteSelectedIndex(0);
      setTimeout(() => inputRef.current?.focus(), 50);
    } else setShowPalette(false);
  };
  const handleSelectCommand = (command: CommandPaletteItem) => {
    setInputQuery(command.template || command.prefix); setShowPalette(false);
    setTimeout(() => inputRef.current?.focus(), 50);
  };
  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (showPalette && filteredCommands.length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        setPaletteSelectedIndex(previous => (previous + (event.key === 'ArrowDown' ? 1 : filteredCommands.length - 1)) % filteredCommands.length);
        return;
      }
      if (event.key === 'Tab') {
        event.preventDefault();
        if (filteredCommands[paletteSelectedIndex]) handleSelectCommand(filteredCommands[paletteSelectedIndex]);
        return;
      }
      if (event.key === 'Enter') {
        if (!inputQuery.includes(' ') && filteredCommands[paletteSelectedIndex]) {
          event.preventDefault(); handleSelectCommand(filteredCommands[paletteSelectedIndex]); return;
        }
        setShowPalette(false); handleSendMessage(); return;
      }
      if (event.key === 'Escape') { event.preventDefault(); setShowPalette(false); return; }
    }
    if (event.key === 'Enter') handleSendMessage();
  };
  if (!isOpen) return null;
  const sendDisabled = isStreaming || !inputQuery.trim() || connectionState !== 'ready';

  return (
    <div style={{ position: 'fixed', right: 0, top: 0, width: '460px', height: '100vh', backgroundColor: '#ffffff',
      boxShadow: '-4px 0 20px rgba(0,0,0,0.12)', display: 'flex', flexDirection: 'column', zIndex: 9999,
      borderLeft: '1px solid #e2e8f0', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif' }}>
      <ChatHeader caseId={caseId} onClose={onClose} />
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column', gap: '16px', backgroundColor: '#f8fafc' }}>
        {messages.length === 0 && <WelcomeGuide onSelectPrompt={handleSendMessage} />}
        {messages.map(message => (
          <div key={message.id} data-turn-id={message.turnId} data-role={message.role}
            style={{ display: 'flex', flexDirection: message.role === 'user' ? 'row-reverse' : 'row', gap: '10px', alignItems: 'flex-start' }}>
            <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: message.role === 'user' ? '#3b82f6' : '#0f172a',
              color: '#ffffff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              {message.role === 'user' ? <User size={16} /> : <Bot size={16} color="#38bdf8" />}
            </div>
            <div style={{ maxWidth: '85%' }}>
              {message.role === 'assistant' && message.thinkingContent && <ThinkingPanel content={message.thinkingContent} isStreaming={isStreaming && !message.turnDone} />}
              {message.content && <div style={{ padding: '10px 14px', borderRadius: '10px', fontSize: '13px', lineHeight: '1.6', whiteSpace: 'pre-wrap',
                backgroundColor: message.role === 'user' ? '#2563eb' : '#ffffff', color: message.role === 'user' ? '#ffffff' : '#1e293b',
                boxShadow: '0 1px 3px rgba(0,0,0,0.06)', border: message.role === 'user' ? 'none' : '1px solid #e2e8f0' }}>{message.content}</div>}
              {message.role === 'assistant' && message.errorText && <div style={{ marginTop: message.content ? '8px' : '0', padding: '10px 12px',
                borderRadius: '8px', fontSize: '12px', lineHeight: '1.5', backgroundColor: '#fef2f2', color: '#991b1b', border: '1px solid #fecaca',
                display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <div>⚠️ {message.errorText}</div>
                {message.retryQuery && !isStreaming && <button type="button" disabled={connectionState !== 'ready'} onClick={() => handleSendMessage(message.retryQuery)}
                  style={{ alignSelf: 'flex-start', padding: '4px 10px', fontSize: '12px', borderRadius: '6px', border: '1px solid #f87171', backgroundColor: '#ffffff', color: '#b91c1c', cursor: 'pointer' }}>重试该提问</button>}
              </div>}
              {message.role === 'assistant' && (message.interactiveCards?.length ? message.interactiveCards : message.interactiveCard ? [message.interactiveCard] : []).map(card => {
                const state = message.submittedCardMap?.[card.actionId];
                const single = message.interactiveCard?.actionId === card.actionId;
                return <InteractiveCard key={card.actionId} card={card}
                  submitted={state ? state.submitted : single ? message.cardSubmitted : false}
                  ticketId={state ? state.ticketId : single ? message.ticketId : undefined} onSubmit={submitCard} />;
              })}
              {message.role === 'assistant' && message.recommendQuestions && <QuestionChips questions={message.recommendQuestions} onSelect={handleSendMessage} />}
            </div>
          </div>
        ))}
        {currentProgress && <div role="status" style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: '#64748b', padding: '6px 12px',
          backgroundColor: '#e2e8f0', borderRadius: '6px', alignSelf: 'flex-start' }}><RefreshCw size={12} className="spin-animate" /><span>{currentProgress}</span></div>}
        <div ref={messagesEndRef} />
      </div>
      <div style={{ padding: '14px 16px', borderTop: '1px solid #e2e8f0', backgroundColor: '#ffffff', position: 'relative' }}>
        {showPalette && <CommandPalette commands={filteredCommands} selectedIndex={paletteSelectedIndex} onSelect={handleSelectCommand} />}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', backgroundColor: '#f1f5f9', borderRadius: '8px', padding: '6px 10px' }}>
          <button type="button" onClick={togglePalette} title="快捷指令面板 (输入 / 或 # 唤起)"
            style={{ background: showPalette ? '#dbeafe' : 'transparent', border: 'none', borderRadius: '6px', padding: '4px 6px', cursor: 'pointer',
              display: 'flex', alignItems: 'center', justifyContent: 'center', color: showPalette ? '#2563eb' : '#64748b', transition: 'background-color 0.15s, color 0.15s' }}><Zap size={16} /></button>
          <input ref={inputRef} aria-label="提问内容" type="text" placeholder={isStreaming ? 'AI 正在分析回复中...' : connectionState === 'connecting' ? '正在建立实时通道...' : '输入 / 或 # 唤起指令，或输入诉求...'}
            disabled={isStreaming} value={inputQuery} onChange={event => handleInputChange(event.target.value)} onKeyDown={handleKeyDown}
            style={{ flex: 1, border: 'none', outline: 'none', backgroundColor: 'transparent', fontSize: '13px', color: '#1e293b' }} />
          <button aria-label="发送提问" onClick={() => handleSendMessage()} disabled={sendDisabled}
            style={{ backgroundColor: sendDisabled ? '#cbd5e1' : '#2563eb', color: '#ffffff', border: 'none', borderRadius: '6px', padding: '6px 12px',
              cursor: sendDisabled ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px' }}><Send size={13} />发送</button>
        </div>
      </div>
    </div>
  );
};
