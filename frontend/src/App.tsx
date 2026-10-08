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
      {/* 模拟现有企业运营业务系统顶部导航 */}
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
          <span>🏢 企业服务运营中台 / 客户支持工作台</span>
        </div>
        <div style={{ fontSize: '13px', color: '#94a3b8' }}>
          当前登录专员：客服专家007（服务运营一组）
        </div>
      </header>

      {/* 模拟服务工单与争议处理主界面 */}
      <main style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
        {/* 业务工单基本卡片 */}
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
                服务单档案：{currentCaseId}
              </span>
              <span style={{
                fontSize: '12px',
                backgroundColor: '#fef3c7',
                color: '#d97706',
                padding: '3px 8px',
                borderRadius: '4px',
                fontWeight: 600
              }}>
                处理中 (费用争议调解)
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
              <Bot size={16} /> 呼起智能业务助手
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', fontSize: '13px' }}>
            <div><span style={{ color: '#64748b' }}>客户姓名：</span>张*（110101********1234）</div>
            <div><span style={{ color: '#64748b' }}>关联订单额：</span>￥12,500.00</div>
            <div><span style={{ color: '#64748b' }}>争议服务费：</span>￥500.00</div>
            <div><span style={{ color: '#64748b' }}>扣款状态：</span>渠道扣划超限校验</div>
          </div>
        </div>

        {/* 模拟服务跟进记录与操作区 */}
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>
          <div style={{
            backgroundColor: '#ffffff',
            borderRadius: '10px',
            padding: '20px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)'
          }}>
            <h3 style={{ fontSize: '15px', color: '#1e293b', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <FileText size={16} /> 服务沟通与客户诉求登记
            </h3>
            <div style={{ fontSize: '13px', color: '#475569', lineHeight: '1.8' }}>
              <div style={{ padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <strong>2026-10-07 15:30 在线客服跟进：</strong>客户反馈近期遭遇不可抗力突发事件，已将医院材料凭证发送至官方支持邮箱，申请减免/折让争议服务费用与违约金。
              </div>
              <div style={{ padding: '8px 0' }}>
                <strong>2026-10-05 10:12 系统代扣处理：</strong>发起定期服务费扣划 1,300 元，支付通道返回 `ERR_DEDUCT_201`，单日银行卡快捷支付超限。
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
              <ShieldCheck size={16} color="#16a34a" /> 业务操作指引
            </h3>
            <p style={{ fontSize: '13px', color: '#64748b', lineHeight: '1.6' }}>
              当客户提出特殊困难争议减免或特批报备时，严禁专员私自承诺。请点击右侧<strong>“呼起智能业务助手”</strong>录入情况，由系统根据企业规范自动给出合规方案试算并一键提报 BPM 审批。
            </p>
          </div>
        </div>
      </main>

      {/* 嵌入的智能协同助手抽屉组件 */}
      <ChatDrawer
        isOpen={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        caseId={currentCaseId}
      />
    </div>
  );
};
