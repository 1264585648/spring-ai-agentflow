import React, { useState } from 'react';
import { ChatDrawer } from './components/ChatDrawer';
import { Bot, Server, Activity, AlertCircle, ShieldCheck } from 'lucide-react';

export const App: React.FC = () => {
  const [drawerOpen, setDrawerOpen] = useState<boolean>(true);
  const [currentService] = useState<string>('order-service');

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: '#f1f5f9',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
    }}>
      {/* 顶部导航 */}
      <header style={{
        height: '56px',
        backgroundColor: '#0f172a',
        color: '#ffffff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 24px'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontWeight: 600, fontSize: '16px' }}>
          <span>🩺 企业级智能运维与多智能体排障工作台 / SRE 协同中台</span>
        </div>
        <div style={{ fontSize: '13px', color: '#94a3b8' }}>
          当前登录协同专员：SRE 值班架构师 (订单核心链路)
        </div>
      </header>

      {/* 主界面内容 */}
      <main style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
        {/* 服务监控基本卡片 */}
        <div style={{
          backgroundColor: '#ffffff',
          borderRadius: '10px',
          padding: '20px',
          boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
          marginBottom: '20px'
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span style={{ fontSize: '18px', fontWeight: 700, color: '#1e293b', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Server size={20} color="#2563eb" /> 当前排查服务：{currentService}
              </span>
              <span style={{
                fontSize: '12px',
                backgroundColor: '#fee2e2',
                color: '#dc2626',
                padding: '3px 8px',
                borderRadius: '4px',
                fontWeight: 600
              }}>
                监控告警：P1 504 Gateway Timeout
              </span>
            </div>

            <button
              onClick={() => setDrawerOpen(true)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
                padding: '8px 16px',
                backgroundColor: '#2563eb',
                color: '#ffffff',
                border: 'none',
                borderRadius: '6px',
                cursor: 'pointer',
                fontWeight: 500,
                fontSize: '13px',
                boxShadow: '0 2px 4px rgba(37,99,235,0.2)'
              }}
            >
              <Bot size={16} /> 呼起智能排障助手
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', fontSize: '13px' }}>
            <div><span style={{ color: '#64748b' }}>部署集群环境：</span><code>prod-k8s-cluster-01</code></div>
            <div><span style={{ color: '#64748b' }}>数据库连接池：</span>HikariCP (活跃 50 / 空闲 0 ⚠️)</div>
            <div><span style={{ color: '#64748b' }}>最近10分钟报错：</span>142 次 (504 超时)</div>
            <div><span style={{ color: '#64748b' }}>微内核架构：</span>Java 21 + Spring AI 2.0</div>
          </div>
        </div>

        {/* 运维待办与 AI 协同指南 */}
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>
          <div style={{
            backgroundColor: '#ffffff',
            borderRadius: '10px',
            padding: '20px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
          }}>
            <h3 style={{ fontSize: '15px', color: '#1e293b', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <AlertCircle size={16} color="#ef4444" /> 实时故障日志与告警待办
            </h3>
            <div style={{ fontSize: '13px', color: '#475569', lineHeight: '1.8' }}>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <span style={{ backgroundColor: '#fee2e2', color: '#dc2626', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>P1 待排查</span>
                <strong>[order-service]: Connection is not available, request timed out after 30000ms</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  数据库连接池打满导致大量上游网关 504 超时，已在右侧助手支持自动关联慢查与止血。
                </div>
              </div>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <span style={{ backgroundColor: '#fef3c7', color: '#d97706', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>DB 慢查</span>
                <strong>[order_db]: SELECT * FROM t_order WHERE status = 1 (持续执行 45s)</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  全表扫描引起数据库锁等待，建议由 SRE 专家装配 Kill 卡片一键止血。
                </div>
              </div>
              <div style={{ padding: '8px 0' }}>
                <span style={{ backgroundColor: '#e0e7ff', color: '#4338ca', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>处置待办</span>
                <strong>[应急止血方案]: 临时调大连接池至 50，并 Kill 慢会话 trx_10423</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  符合 Human-in-the-loop 规范，需工程师在右侧助手卡片中二次确认后执行。
                </div>
              </div>
            </div>
          </div>

          <div style={{
            backgroundColor: '#ffffff',
            borderRadius: '10px',
            padding: '20px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
          }}>
            <h3 style={{ fontSize: '15px', color: '#1e293b', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <ShieldCheck size={16} color="#16a34a" /> 排障安全规范与风控指引
            </h3>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6', marginBottom: '12px' }}>
              <strong>Human-in-the-loop 防线：</strong>大模型在分析日志与慢查后，<strong>严禁直接私自执行高危写操作或杀会话</strong>。系统将自动下发标准化应急卡片，由工程师核对后一键确认提交。
            </p>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6' }}>
              <strong>L1 极速指令：</strong>点击右侧<strong>“呼起智能排障助手”</strong>，输入 <code>#ping</code>、<code>/help</code> 或 <code>/504</code> 即可体验毫秒级直通响应。
            </p>
          </div>
        </div>
      </main>

      {/* 嵌入的智能协同助手抽屉组件 */}
      <ChatDrawer
        isOpen={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        caseId={currentService}
      />
    </div>
  );
};
