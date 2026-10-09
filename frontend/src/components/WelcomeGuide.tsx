import React from 'react';
import { ShieldAlert } from 'lucide-react';

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
      <ShieldAlert size={36} color="#38bdf8" style={{ margin: '0 auto 10px' }} />
      <div style={{ fontWeight: 600, color: '#334155', fontSize: '14px' }}>
        GitHub 研发协同多智能体助手已就绪
      </div>
      <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '16px' }}>
        点击下方快捷指令或输入问题直接体验：
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
            (L1指令帮助菜单直出)
          </span>
        </button>
        <button
          onClick={() => onSelectPrompt('/repo spring-projects/spring-ai')}
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
          <span>🔍</span> <strong>/repo spring-projects/spring-ai</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (L1正则提取参数查仓库)
          </span>
        </button>
        <button
          onClick={() =>
            onSelectPrompt(
              '在 spring-projects/spring-ai 仓库下，高并发压测时 Redis 连接池偶发泄漏抛出 RedisConnectionException，如何排查并提报 Issue？'
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
          <span>🐛</span> <strong>Redis 泄漏排查与 Issue 提单协同</strong>{' '}
          <span style={{ color: '#64748b', fontSize: '11px' }}>
            (专家分析 + 预填卡片)
          </span>
        </button>
      </div>
    </div>
  );
};
