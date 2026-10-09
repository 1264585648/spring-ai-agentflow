import React, { useState } from 'react';
import { ChatDrawer } from './components/ChatDrawer';
import { Bot, GitPullRequest, GitBranch, AlertCircle, CheckCircle2, ShieldCheck, Terminal } from 'lucide-react';

export const App: React.FC = () => {
  const [drawerOpen, setDrawerOpen] = useState<boolean>(true);
  const [currentRepo, setCurrentRepo] = useState<string>('spring-projects/spring-ai');

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
          <span>🐙 GitHub 研发协同与开源运维工作台 / DevOps 开发者中台</span>
        </div>
        <div style={{ fontSize: '13px', color: '#94a3b8' }}>
          当前登录协同专员：Core Maintainer (Spring AI 研发组)
        </div>
      </header>

      {/* 主界面内容 */}
      <main style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
        {/* 仓库状态基本卡片 */}
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
                <GitBranch size={20} color="#2563eb" /> 协同目标仓库：{currentRepo}
              </span>
              <span style={{
                fontSize: '12px',
                backgroundColor: '#dcfce7',
                color: '#16a34a',
                padding: '3px 8px',
                borderRadius: '4px',
                fontWeight: 600
              }}>
                CI/CD 运行正常 (v2.0.0-SNAPSHOT)
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
              <Bot size={16} /> 呼起研发协同助手
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', fontSize: '13px' }}>
            <div><span style={{ color: '#64748b' }}>默认主分支：</span><code>main</code> (HEAD)</div>
            <div><span style={{ color: '#64748b' }}>活跃待审 PR：</span>18 项 (含 #518 状态机)</div>
            <div><span style={{ color: '#64748b' }}>待排查 Issue：</span>42 项 (含 #1024 压测泄漏)</div>
            <div><span style={{ color: '#64748b' }}>微内核架构：</span>Java 21 + Spring AI 2.0</div>
          </div>
        </div>

        {/* 研发待办与 AI 协同指南 */}
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>
          <div style={{
            backgroundColor: '#ffffff',
            borderRadius: '10px',
            padding: '20px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
          }}>
            <h3 style={{ fontSize: '15px', color: '#1e293b', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <AlertCircle size={16} color="#ef4444" /> 近期构建告警与 Issue 缺陷待办
            </h3>
            <div style={{ fontSize: '13px', color: '#475569', lineHeight: '1.8' }}>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <span style={{ backgroundColor: '#fee2e2', color: '#dc2626', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>P1 待排查</span>
                <strong>[Issue #1024]: Redis 连接池高并发压测偶发句柄泄漏</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  压测 QPS 超过 1500 时连接池耗尽抛出 RedisConnectionException，已在右侧助手支持自动排障与提单。
                </div>
              </div>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <span style={{ backgroundColor: '#fef3c7', color: '#d97706', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>CI 告警</span>
                <strong>[Actions #512]: GitHub Actions maven-test 单元测试超时</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  单元测试 JedisConnectionPool 等待空闲对象超时，建议查阅 CI 运行记录。
                </div>
              </div>
              <div style={{ padding: '8px 0' }}>
                <span style={{ backgroundColor: '#e0e7ff', color: '#4338ca', padding: '2px 6px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, marginRight: '8px' }}>PR 待审查</span>
                <strong>[PR #518]: feat: 添加动态智能体元数据管理与生命周期状态机</strong>
                <div style={{ color: '#64748b', fontSize: '12px', marginTop: '2px' }}>
                  新增 sys_agent_definition 表持久化与核心保护锁，待 Maintainer 审查 Diff。
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
              <ShieldCheck size={16} color="#16a34a" /> 智能体协同与风控指引
            </h3>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6', marginBottom: '12px' }}>
              <strong>Human-in-the-loop 防线：</strong>AI 在排查缺陷或审核 PR 后，<strong>严禁直接调用 GitHub 写权限接口</strong>。系统将自动装配规范卡片预填表单，由开发者核对后一键确认提交。
            </p>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6' }}>
              <strong>L1 极速指令：</strong>点击右侧<strong>“呼起研发协同助手”</strong>，输入 <code>#ping</code>、<code>/help</code> 或 <code>/repo {currentRepo}</code> 即可体验毫秒级直通响应。
            </p>
          </div>
        </div>
      </main>

      {/* 嵌入的智能协同助手抽屉组件 */}
      <ChatDrawer
        isOpen={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        caseId={currentRepo}
      />
    </div>
  );
};
