export interface RuleItemVO {
  id: number;
  ruleCode: string;
  ruleName: string;
  matchType: string;
  patternExpr: string;
  targetType: string;
  targetRef: string;
  paramTemplate?: string;
  priority: number;
  isEnabled: number;
  hitCount: number;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RuleCreateOrUpdateRequest {
  ruleCode?: string;
  ruleName: string;
  matchType: string;
  patternExpr: string;
  targetType: string;
  targetRef: string;
  paramTemplate?: string;
  priority: number;
  isEnabled?: number;
  description?: string;
}

export interface RuleStatsVO {
  totalRules: number;
  enabledRules: number;
  memoryActiveRules: number;
  totalHits: number;
  snapshotStatus: string;
}
