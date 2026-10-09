import { RuleItemVO, RuleCreateOrUpdateRequest, RuleStatsVO } from '../types/admin';

const BASE_URL = '/api/v1/admin/rules';

export const adminClient = {
  // 查询全量规则列表
  async getRules(): Promise<RuleItemVO[]> {
    const resp = await fetch(`${BASE_URL}`);
    if (!resp.ok) throw new Error(`获取规则列表失败: ${resp.status}`);
    return resp.json();
  },

  // 获取规则运营概览统计指标
  async getRuleStats(): Promise<RuleStatsVO> {
    const resp = await fetch(`${BASE_URL}/stats`);
    if (!resp.ok) throw new Error(`获取规则统计失败: ${resp.status}`);
    return resp.json();
  },

  // 新增规则 (自动触发热重载)
  async createRule(rule: RuleCreateOrUpdateRequest): Promise<RuleItemVO> {
    const resp = await fetch(`${BASE_URL}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(rule),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ error: '创建失败' }));
      throw new Error(err.error || `创建失败: ${resp.status}`);
    }
    return resp.json();
  },

  // 更新规则
  async updateRule(id: number, rule: RuleCreateOrUpdateRequest): Promise<RuleItemVO> {
    const resp = await fetch(`${BASE_URL}/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(rule),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ error: '更新失败' }));
      throw new Error(err.error || `更新失败: ${resp.status}`);
    }
    return resp.json();
  },

  // 启停切换
  async toggleRule(id: number): Promise<RuleItemVO> {
    const resp = await fetch(`${BASE_URL}/${id}/toggle`, {
      method: 'PUT',
    });
    if (!resp.ok) throw new Error(`切换规则状态失败: ${resp.status}`);
    return resp.json();
  },

  // 删除规则
  async deleteRule(id: number): Promise<void> {
    const resp = await fetch(`${BASE_URL}/${id}`, {
      method: 'DELETE',
    });
    if (!resp.ok) throw new Error(`删除规则失败: ${resp.status}`);
  },

  // 手动发布热重载事件
  async reloadRules(): Promise<{ success: boolean; message: string; activeRulesCount?: number }> {
    const resp = await fetch(`${BASE_URL}/reload`, {
      method: 'POST',
    });
    if (!resp.ok) throw new Error(`热重载失败: ${resp.status}`);
    return resp.json();
  },
};
