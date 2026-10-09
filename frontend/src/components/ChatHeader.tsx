import React from 'react';
import { Bot, X } from 'lucide-react';

interface ChatHeaderProps {
  caseId: string;
  onClose: () => void;
}

export const ChatHeader: React.FC<ChatHeaderProps> = ({ caseId, onClose }) => {
  return (
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
          <div style={{ fontSize: '15px', fontWeight: 600 }}>GitHub 研发协同与多智能体助手</div>
          <div style={{ fontSize: '12px', color: '#94a3b8' }}>
            协同目标仓库：<span style={{ color: '#38bdf8' }}>{caseId}</span>
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
  );
};
