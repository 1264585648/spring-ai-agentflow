import React from 'react';
import { CommandPaletteItem } from '../types/chat';
import { Zap, HelpCircle, Wrench, Terminal, CornerDownLeft } from 'lucide-react';

interface CommandPaletteProps {
  commands: CommandPaletteItem[];
  selectedIndex: number;
  onSelect: (command: CommandPaletteItem) => void;
}

export const CommandPalette: React.FC<CommandPaletteProps> = ({
  commands,
  selectedIndex,
  onSelect,
}) => {
  if (commands.length === 0) {
    return (
      <div
        style={{
          position: 'absolute',
          bottom: '100%',
          left: 0,
          right: 0,
          marginBottom: '8px',
          backgroundColor: '#ffffff',
          borderRadius: '8px',
          boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
          border: '1px solid #e2e8f0',
          padding: '12px',
          fontSize: '12px',
          color: '#94a3b8',
          textAlign: 'center',
          zIndex: 50,
        }}
      >
        未找到匹配的快捷指令
      </div>
    );
  }

  const renderIcon = (iconName: string) => {
    switch (iconName) {
      case 'Zap':
        return <Zap size={14} color="#eab308" />;
      case 'Wrench':
        return <Wrench size={14} color="#3b82f6" />;
      default:
        return <HelpCircle size={14} color="#8b5cf6" />;
    }
  };

  return (
    <div
      style={{
        position: 'absolute',
        bottom: '100%',
        left: 0,
        right: 0,
        marginBottom: '8px',
        backgroundColor: '#ffffff',
        borderRadius: '8px',
        boxShadow: '0 6px 20px rgba(0,0,0,0.14)',
        border: '1px solid #cbd5e1',
        overflow: 'hidden',
        zIndex: 50,
        maxHeight: '260px',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* 顶部标题栏 */}
      <div
        style={{
          padding: '6px 12px',
          backgroundColor: '#f8fafc',
          borderBottom: '1px solid #f1f5f9',
          fontSize: '11px',
          color: '#64748b',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontWeight: 600 }}>
          <Terminal size={12} /> 快捷指令菜单 (L1 命令直通)
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '10px' }}>
          <span>按 <kbd style={{ padding: '1px 4px', backgroundColor: '#e2e8f0', borderRadius: '3px' }}>↑</kbd> <kbd style={{ padding: '1px 4px', backgroundColor: '#e2e8f0', borderRadius: '3px' }}>↓</kbd> 选择</span>
          <span><kbd style={{ padding: '1px 4px', backgroundColor: '#e2e8f0', borderRadius: '3px' }}>Tab/Enter</kbd> 补全</span>
        </div>
      </div>

      {/* 指令列表 */}
      <div style={{ overflowY: 'auto', padding: '4px 0' }}>
        {commands.map((cmd, idx) => {
          const isSelected = idx === selectedIndex;
          return (
            <div
              key={cmd.code}
              onClick={() => onSelect(cmd)}
              style={{
                padding: '8px 12px',
                cursor: 'pointer',
                backgroundColor: isSelected ? '#eff6ff' : 'transparent',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                borderLeft: isSelected ? '3px solid #2563eb' : '3px solid transparent',
                transition: 'background-color 0.1s',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', minWidth: 0, overflow: 'hidden' }}>
                {renderIcon(cmd.icon)}
                <span
                  style={{
                    fontFamily: 'monospace',
                    fontWeight: 600,
                    fontSize: '13px',
                    color: isSelected ? '#1d4ed8' : '#0f172a',
                  }}
                >
                  {cmd.prefix}
                </span>
                <span style={{ fontSize: '12px', color: '#475569', fontWeight: 500, flexShrink: 0 }}>
                  {cmd.name}
                </span>
                {cmd.description && (
                  <span
                    style={{
                      fontSize: '11px',
                      color: '#94a3b8',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    - {cmd.description}
                  </span>
                )}
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
                <span
                  style={{
                    fontSize: '10px',
                    padding: '2px 6px',
                    borderRadius: '4px',
                    backgroundColor: cmd.targetType === 'TOOL' ? '#e0f2fe' : '#f1f5f9',
                    color: cmd.targetType === 'TOOL' ? '#0369a1' : '#475569',
                    fontWeight: 500,
                  }}
                >
                  {cmd.targetType === 'TOOL' ? 'Tool反射' : '直通直出'}
                </span>
                {isSelected && <CornerDownLeft size={12} color="#2563eb" />}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
