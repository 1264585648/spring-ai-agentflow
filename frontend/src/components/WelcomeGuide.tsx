import React from 'react';
import { Activity } from 'lucide-react';

interface WelcomeGuideProps {
  onSelectPrompt: (prompt: string) => void;
}

export const WelcomeGuide: React.FC<WelcomeGuideProps> = ({ onSelectPrompt }) => {
  return (
    <div
      style={{
        textAlign: 'center',
        color: '#94a3b8',
        marginTop: '40px',
        fontSize: '13px',
        lineHeight: '1.8',
      }}
    >
      <Activity size={36} color="#38bdf8" style={{ margin: '0 auto 10px' }} />
      <div style={{ fontWeight: 600, color: '#334155', fontSize: '14px' }}>
        企业级智能运维与多智能体排障助手已就绪
      </div>
      <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '16px' }}>
        点击下方快捷指令或输入故障描述直接体验：
      </div>
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
          maxWidth: '340px',
          margin: '0 auto',
        }}
      >
        <button
          onClick={() => onSelectPrompt('#ping')}
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
            boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
          }}
        >
          <span>⚡</span> <strong>#ping</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (L1规则直通探活 &lt;5ms)
          </span>
        </button>
        <button
          onClick={() => onSelectPrompt('/help')}
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
            boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
          }}
        >
          <span>📌</span> <strong>/help</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (L1系统指令帮助菜单直出)
          </span>
        </button>
        <button
          onClick={() => onSelectPrompt('/504')}
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
            boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
          }}
        >
          <span>🚨</span> <strong>/504</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (L1网关超时排查SOP极速直出)
          </span>
        </button>
        <button
          onClick={() =>
            onSelectPrompt(
              'order-service 线上出现 504 Gateway Timeout 报错，日志提示 Hikari 连接超时，请分析日志并排查数据库慢查'
            )
          }
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
            boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
          }}
        >
          <span>🩺</span> <strong>504 超时与连接池慢查联合排障</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (多智能体协同 + 应急止血卡片)
          </span>
        </button>
      </div>
    </div>
  );
};
