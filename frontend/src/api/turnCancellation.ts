/** 取消是尽力请求，不等同于已停止第三方 I/O，也不能取消已经提交的卡片写操作。 */
export interface TurnCancellationResponse { code: number; turnId: string; cancelled: boolean; }

export async function cancelTurn(sessionId: string, turnId: string): Promise<TurnCancellationResponse> {
  if (!sessionId.trim() || !turnId.trim() || sessionId.length > 128 || turnId.length > 128) {
    throw new Error('取消必须提供有效的 sessionId 和原始 turnId');
  }
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { controller.abort(); reject(new Error('取消回执未确认')); }, 5000);
  });
  const operation = async (): Promise<TurnCancellationResponse> => {
    const params = new URLSearchParams({ sessionId, turnId });
    const response = await fetch(`/api/v1/chat/cancel?${params}`, { method: 'POST', signal: controller.signal });
    const body: unknown = await response.json();
    if (!response.ok || !body || typeof body !== 'object') throw new Error('取消回执未确认');
    const result = body as Partial<TurnCancellationResponse>;
    if (result.code !== 200 || result.turnId !== turnId || typeof result.cancelled !== 'boolean') {
      throw new Error('取消回执不完整或回合不匹配');
    }
    return result as TurnCancellationResponse;
  };
  try { return await Promise.race([operation(), deadline]); }
  finally { clearTimeout(timer); }
}
