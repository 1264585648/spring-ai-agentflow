import React from 'react';
import { HelpCircle } from 'lucide-react';

interface QuestionChipsProps {
  questions?: string[];
  onSelect: (q: string) => void;
}

export const QuestionChips: React.FC<QuestionChipsProps> = ({ questions, onSelect }) => {
  if (!questions || questions.length === 0) return null;

  return (
    <div style={{ marginTop: '10px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
      <div style={{ fontSize: '12px', color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '4px' }}>
        <HelpCircle size={13} /> 您可能还想了解：
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
        {questions.map((q, idx) => (
          <button
            key={idx}
            onClick={() => onSelect(q)}
            style={{
              padding: '5px 12px',
              fontSize: '12px',
              backgroundColor: '#eff6ff',
              color: '#2563eb',
              border: '1px solid #bfdbfe',
              borderRadius: '999px',
              cursor: 'pointer',
              transition: 'all 0.15s ease',
              textAlign: 'left'
            }}
            onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = '#dbeafe')}
            onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = '#eff6ff')}
          >
            {q}
          </button>
        ))}
      </div>
    </div>
  );
};
