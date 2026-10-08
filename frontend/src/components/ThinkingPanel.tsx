import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Brain } from 'lucide-react';

interface ThinkingPanelProps {
  content: string;
  isStreaming?: boolean;
}

export const ThinkingPanel: React.FC<ThinkingPanelProps> = ({ content, isStreaming }) => {
  const [expanded, setExpanded] = useState<boolean>(true);

  if (!content) return null;

  return (
    <div style={{
      marginBottom: '10px',
      border: '1px solid #e2e8f0',
      borderRadius: '8px',
      backgroundColor: '#f8fafc',
      overflow: 'hidden',
      fontSize: '13px'
    }}>
      <div
        onClick={() => setExpanded(!expanded)}
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '8px 12px',
          cursor: 'pointer',
          color: '#64748b',
          userSelect: 'none'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <Brain size={15} color="#3b82f6" />
          <span style={{ fontWeight: 500, color: '#334155' }}>
            {isStreaming ? '正在检索业务规范与政策依据...' : '深度思考过程 (已折叠)'}
          </span>
        </div>
        {expanded ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
      </div>

      {expanded && (
        <div style={{
          padding: '10px 12px',
          borderTop: '1px solid #f1f5f9',
          color: '#475569',
          lineHeight: '1.6',
          whiteSpace: 'pre-wrap',
          backgroundColor: '#ffffff'
        }}>
          {content}
        </div>
      )}
    </div>
  );
};
