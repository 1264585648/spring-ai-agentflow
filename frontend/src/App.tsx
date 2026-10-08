import React, { useState } from 'react';
import { ChatDrawer } from './components/ChatDrawer';
import { Bot, FileText, PhoneCall, AlertTriangle, ShieldCheck } from 'lucide-react';

export const App: React.FC = () => {
  const [drawerOpen, setDrawerOpen] = useState<boolean>(true);
  const [currentCaseId, setCurrentCaseId] = useState<string>('CASE_10086');

  return (
    <div style={{
      minHeight: '100vh',
      backgroundColor: '#f1f5f9',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
    }}>
      {/* 模拟现有催收系统顶部导航 */}
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
          <span>🏦 催收业务中台 / 坐席作业工作台</span>
        </div>
        <div style={{ fontSize: '13px', color: '#94a3b8' }}>
          当前登录催收员：坐席007（催收一组）
        </div>
      </header>

      {/* 模拟催收案件工作流主界面 */}
      <main style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
        {/* 案件基本卡片 */}
        <div style={{
          backgroundColor: '#ffffff',
          borderRadius: '10px',
          padding: '20px',
          boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
          marginBottom: '20px'
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span style={{ fontSize: '18px', fontWeight: 700, color: '#1e293b' }}>
                案件档案：{currentCaseId}
              </span>
              <span style={{
                fontSize: '12px',
                backgroundColor: '#fee2e2',
                color: '#b91c1c',
                padding: '3px 8px',
                borderRadius: '4px',
                fontWeight: 600
              }}>
                逾期 M2 (62 天)
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
              <Bot size={16} /> 呼起催收答疑助手
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', fontSize: '13px' }}>
            <div><span style={{ color: '#64748b' }}>借款人：</span>张*（110101********1234）</div>
            <div><span style={{ color: '#64748b' }}>逾期本金：</span>￥12,500.00</div>
            <div><span style={{ color: '#64748b' }}>应计罚息：</span>￥500.00</div>
            <div><span style={{ color: '#64748b' }}>划扣状态：</span>扣划限额失败</div>
          </div>
        </div>

        {/* 模拟催收跟进记录与操作区 */}
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>
          <div style={{
            backgroundColor: '#ffffff',
            borderRadius: '10px',
            padding: '20px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
          }}>
            <h3 style={{ fontSize: '15px', color: '#1e293b', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <FileText size={16} /> 案件催记与客户抗辩记录
            </h3>
            <div style={{ fontSize: '13px', color: '#475569', lineHeight: '1.8' }}>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <strong>2026-10-07 15:30 外呼记录：</strong>客户声称近期在市人民医院进行重大疾病手术，已将住院证明发送至客服邮箱，表示目前无劳动收入，强烈申请减免逾期利息与罚息。
              </div>
              <div style={{ padding: '8px 0' }}>
                <strong>2026-10-05 10:12 系统代扣：</strong>发起到期本息代扣 1,300 元，银行网关返回 `ERR_DEDUCT_201`，单日银行卡快捷支付超限。
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
              <ShieldCheck size={16} color="#16a34a" /> 坐席操作指引
            </h3>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6' }}>
              当客户提出特殊困难减免或要求停催时，严禁坐席私自承诺。请点击右侧<strong>“AI智能答疑”</strong>输入借款人情况，由系统自动给出合规减免试算并直接提报 BPM 审批。
            </p>
          </div>
        </div>
      </main>

      {/* 嵌入的催收答疑机器人抽屉组件 */}
      <ChatDrawer
        isOpen={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        caseId={currentCaseId}
      />
    </div>
  );
};
