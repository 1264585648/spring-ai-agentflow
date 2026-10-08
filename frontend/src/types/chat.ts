/**
 * SSE 事件类型枚举，严格对齐后端 SseEventType.java
 */
export type SseEventType =
  | 'thinking'
  | 'message'
  | 'progress'
  | 'interactive_card'
  | 'recommend_questions'
  | 'conversation_title'
  | 'done'
  | 'error';

/**
 * 通用交互卡片表单字段模型
 */
export interface CardFormField {
  fieldKey: string;
  label: string;
  type: 'text' | 'number' | 'textarea' | 'select';
  value: string | number;
  maxLimit?: number;
  minLimit?: number;
  editable: boolean;
  required: boolean;
  placeholder?: string;
}

/**
 * 通用人机协同交互卡片模型 (InteractiveCardData)
 */
export interface InteractiveCardData {
  actionId: string;
  cardType: string;
  title: string;
  description: string;
  fields: CardFormField[];
  confirmButtonText: string;
  cancelButtonText: string;
}

/**
 * 进度通知载荷
 */
export interface ProgressData {
  stage: string;
  description: string;
}

/**
 * 会话单条消息模型
 */
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  thinkingContent?: string;
  progressList?: ProgressData[];
  interactiveCard?: InteractiveCardData;
  cardSubmitted?: boolean;
  ticketId?: string;
  recommendQuestions?: string[];
  timestamp: number;
}
