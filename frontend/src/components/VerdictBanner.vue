<script setup>
defineProps({
  verdict: { type: Object, required: true }
})
</script>

<template>
  <section class="verdict glass" :class="'verdict--' + verdict.grade">
    <div class="verdict-left">
      <h2 class="verdict-label">
        <span class="verdict-icon" aria-hidden="true">
          <svg v-if="verdict.grade === 'PASS'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
          <svg v-else-if="verdict.grade === 'OK'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="8 12 11 15 16 9"/></svg>
          <svg v-else-if="verdict.grade === 'WARN'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
        </span>
        {{ verdict.label }}
      </h2>
      <p class="verdict-desc">{{ verdict.desc }}</p>
    </div>
    <div class="verdict-counts">
      <span v-if="verdict.high" class="vc vc--high">{{ verdict.high }} 条 HIGH</span>
      <span v-if="verdict.medium" class="vc vc--medium">{{ verdict.medium }} 条 MEDIUM</span>
      <span v-if="verdict.low" class="vc vc--low">{{ verdict.low }} 条 LOW</span>
    </div>
  </section>
</template>

<style scoped>
/* 结论不仅靠颜色区分：每个等级带独立图标，色弱用户也可辨识 */
.verdict {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-radius: var(--radius-lg);
}
/* 语义色做半透明底色，配合玻璃模糊形成柔和光晕 */
.verdict--PASS { background: linear-gradient(135deg, var(--green-soft), transparent 70%), var(--glass-bg); border-color: rgba(5, 150, 105, 0.45); }
.verdict--OK   { background: linear-gradient(135deg, var(--blue-soft), transparent 70%), var(--glass-bg);  border-color: rgba(2, 132, 199, 0.45); }
.verdict--WARN { background: linear-gradient(135deg, var(--amber-soft), transparent 70%), var(--glass-bg); border-color: rgba(217, 119, 6, 0.5); }
.verdict--FAIL { background: linear-gradient(135deg, var(--red-soft), transparent 70%), var(--glass-bg);   border-color: rgba(225, 29, 72, 0.45); }

.verdict-left { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.verdict-label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 18px;
  font-weight: 700;
}
.verdict--PASS .verdict-label { color: var(--green); }
.verdict--OK   .verdict-label { color: var(--blue); }
.verdict--WARN .verdict-label { color: var(--amber); }
.verdict--FAIL .verdict-label { color: var(--red); }
.verdict-icon { display: inline-flex; }

.verdict-desc { font-size: 14px; color: var(--fg-muted); }

.verdict-counts { display: flex; gap: var(--space-2); flex-shrink: 0; }
.vc {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  padding: 5px 12px;
  border-radius: 16px;
  border: 1px solid transparent;
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}
.vc--high   { background: var(--red-soft);   color: var(--red);   border-color: rgba(225,29,72,0.35); }
.vc--medium { background: var(--amber-soft); color: var(--amber); border-color: rgba(217,119,6,0.4); }
.vc--low    { background: var(--blue-soft);  color: var(--blue);  border-color: rgba(2,132,199,0.35); }

@media (max-width: 640px) {
  .verdict { flex-direction: column; align-items: flex-start; }
  .verdict-label { font-size: 16px; }
}
</style>
