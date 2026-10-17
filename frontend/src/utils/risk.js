/**
 * 与后端 RiskMerger 相同的去重键：file|line|message。
 * 前端用它作为展开/复制状态的稳定标识，避免切换级别过滤后下标错位到另一条风险。
 */
export function riskKey(risk) {
  return `${risk?.file ?? ''}|${risk?.line ?? 0}|${risk?.message ?? ''}`
}
