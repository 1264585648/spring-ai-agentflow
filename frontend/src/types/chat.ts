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
 * SSE 长连接状态枚举 (F08: 将连接状态与对话回合状态解耦)
 */
export type SseConnectionState = 'disconnected' | 'connecting' | 'ready' | 'error';

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
  turnId?: string;
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
  turnId?: string;
  sequence?: number;
}

/**
 * 会话单条消息模型
 */
export interface ChatMessage {
  id: string;
  turnId?: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  thinkingContent?: string;
  progressList?: ProgressData[];
  interactiveCard?: InteractiveCardData;
  interactiveCards?: InteractiveCardData[];
  cardSubmitted?: boolean;
  ticketId?: string;
  submittedCardMap?: Record<string, { submitted: boolean; ticketId?: string }>;
  recommendQuestions?: string[];
  errorText?: string;
  retryQuery?: string;
  turnDone?: boolean;
  timestamp: number;
}

/**
 * 快捷指令面板项模型
 */
export interface CommandPaletteItem {
  code: string;
  prefix: string;
  name: string;
  template: string;
  description: string;
  targetType: string;
  icon: string;
}


