import React, { useState } from 'react';
import { InteractiveCardData } from '../types/chat';
import { FileCheck, Send, CheckCircle2, AlertCircle } from 'lucide-react';

interface InteractiveCardProps {
  card: InteractiveCardData;
  submitted?: boolean;
  ticketId?: string;
  onSubmit: (actionId: string, cardType: string, formValues: Record<string, any>) => Promise<string | void>;
}

export const InteractiveCard: React.FC<InteractiveCardProps> = ({
  card,
  submitted = false,
  ticketId,
  onSubmit,
}) => {
  // 提取默认值
  const initialValues: Record<string, any> = {};
  card.fields.forEach((field) => {
    initialValues[field.fieldKey] = field.value;
  });

  const [formData, setFormData] = useState<Record<string, any>>(initialValues);
  const [loading, setLoading] = useState<boolean>(false);
  const [successId, setSuccessId] = useState<string | undefined>(ticketId);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const isLocked = submitted || !!successId;

  const handleFieldChange = (fieldKey: string, val: any) => {
    if (isLocked) return;
    setFormData((prev) => ({ ...prev, [fieldKey]: val }));
  };

  const handleConfirmSubmit = async () => {
    if (isLocked || loading) return;

    // 简单校验上限
    for (const field of card.fields) {
      if (field.maxLimit !== undefined && typeof formData[field.fieldKey] === 'number') {
        if (Number(formData[field.fieldKey]) > Number(field.maxLimit)) {
          setErrorMsg(`${field.label} 不能超过上限限制 ${field.maxLimit}`);
          return;
        }
      }
    }

    setLoading(true);
    setErrorMsg(null);
    try {
      const id = await onSubmit(card.actionId, card.cardType, formData);
      if (id) {
        setSuccessId(id);
      }
    } catch (err: any) {
      setErrorMsg(err.message || '方案提交处理失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      margin: '12px 0',
      border: '1.5px solid #cbd5e1',
      borderRadius: '10px',
      backgroundColor: '#ffffff',
      boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.05)',
      overflow: 'hidden'
    }}>
      {/* 头部标题栏 */}
      <div style={{
        padding: '12px 16px',
        backgroundColor: '#f1f5f9',
        borderBottom: '1px solid #e2e8f0',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <FileCheck size={18} color="#2563eb" />
          <span style={{ fontWeight: 600, color: '#1e293b', fontSize: '14px' }}>
            {card.title}
          </span>
        </div>
        {successId && (
          <span style={{
            fontSize: '12px',
            backgroundColor: '#dcfce7',
            color: '#15803d',
            padding: '2px 8px',
            borderRadius: '999px',
            display: 'flex',
            alignItems: 'center',
            gap: '4px'
          }}>
            <CheckCircle2 size={12} /> 已提交
          </span>
        )}
      </div>

      {/* 方案依据说明 */}
      {card.description && (
        <div style={{
          padding: '10px 16px',
          fontSize: '13px',
          color: '#475569',
          backgroundColor: '#f8fafc',
          borderBottom: '1px dashed #e2e8f0',
          lineHeight: '1.5'
        }}>
          💡 <strong>方案依据说明：</strong>{card.description}
        </div>
      )}

      {/* 动态表单项列表 */}
      <div style={{ padding: '16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
        {card.fields.map((field) => (
          <div key={field.fieldKey} style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            <label style={{ fontSize: '12px', fontWeight: 500, color: '#64748b' }}>
              {field.label} {field.required && <span style={{ color: '#ef4444' }}>*</span>}
              {!field.editable && <span style={{ color: '#94a3b8', marginLeft: '6px' }}>(系统锁定)</span>}
            </label>

            {field.type === 'textarea' ? (
              <textarea
                disabled={!field.editable || isLocked}
                value={formData[field.fieldKey] || ''}
                onChange={(e) => handleFieldChange(field.fieldKey, e.target.value)}
                rows={2}
                placeholder={field.placeholder}
                style={{
                  padding: '8px 10px',
                  borderRadius: '6px',
                  border: '1px solid #cbd5e1',
                  fontSize: '13px',
                  backgroundColor: !field.editable || isLocked ? '#f8fafc' : '#ffffff',
                  color: '#1e293b',
                  resize: 'vertical'
                }}
              />
            ) : (
              <input
                type={field.type === 'number' ? 'number' : 'text'}
                disabled={!field.editable || isLocked}
                value={formData[field.fieldKey] ?? ''}
                placeholder={field.placeholder}
                onChange={(e) =>
                  handleFieldChange(
                    field.fieldKey,
                    field.type === 'number' ? parseFloat(e.target.value) || 0 : e.target.value
                  )
                }
                style={{
                  padding: '7px 10px',
                  borderRadius: '6px',
                  border: '1px solid #cbd5e1',
                  fontSize: '13px',
                  backgroundColor: !field.editable || isLocked ? '#f8fafc' : '#ffffff',
                  color: '#1e293b'
                }}
              />
            )}
          </div>
        ))}

        {errorMsg && (
          <div style={{ color: '#ef4444', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <AlertCircle size={14} /> {errorMsg}
          </div>
        )}
      </div>

      {/* 底部操作区 */}
      <div style={{
        padding: '12px 16px',
        backgroundColor: '#fafafa',
        borderTop: '1px solid #f1f5f9',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between'
      }}>
        {successId ? (
          <div style={{ fontSize: '13px', color: '#15803d', fontWeight: 500 }}>
            流转单号：<strong>{successId}</strong>（已进入处理管道）
          </div>
        ) : (
          <div style={{ fontSize: '12px', color: '#94a3b8' }}>
            请核验方案参数，确认后将调用后台处理器
          </div>
        )}

        {!isLocked && (
          <button
            onClick={handleConfirmSubmit}
            disabled={loading}
            style={{
              padding: '8px 16px',
              backgroundColor: loading ? '#93c5fd' : '#2563eb',
              color: '#ffffff',
              border: 'none',
              borderRadius: '6px',
              cursor: loading ? 'not-allowed' : 'pointer',
              fontSize: '13px',
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: '6px'
            }}
          >
            <Send size={14} />
            {loading ? '正在提交...' : card.confirmButtonText || '确认提交'}
          </button>
        )}
      </div>
    </div>
  );
};
