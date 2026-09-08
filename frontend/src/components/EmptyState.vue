<script setup>
defineProps({
  examples: { type: Array, default: () => [] },
  history: { type: Array, default: () => [] }
})
const emit = defineEmits(['select', 'clear-history'])
</script>

<template>
  <section class="empty">
    <svg class="empty-icon" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
      <polyline points="8 11 11 14 15 9"/>
    </svg>
    <h1 class="empty-title">粘贴 GitHub PR 地址，开始 AI 评审</h1>
    <p class="empty-desc">ReAct Agent 会自主阅读 diff、按需拉取文件与仓库搜索，输出结构化风险报告。</p>

    <div v-if="examples.length" class="row">
      <span class="row-label">试试示例：</span>
      <button
        v-for="ex in examples" :key="ex.url"
        class="chip"
        @click="emit('select', ex.url)"
      >{{ ex.label }}</button>
    </div>

    <div v-if="history.length" class="row">
      <span class="row-label">最近评审：</span>
      <button
        v-for="h in history" :key="h.url"
        class="chip chip--history"
        @click="emit('select', h.url)"
      >{{ h.label }}</button>
      <button class="clear-btn" @click="emit('clear-history')">清除记录</button>
    </div>
  </section>
</template>

<style scoped>
.empty {
  text-align: center;
  padding: var(--space-7) 0 var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
}
.empty-icon { color: var(--border-strong); }
.empty-title { font-size: 18px; font-weight: 600; color: var(--fg); }
.empty-desc { font-size: 14px; color: var(--fg-muted); max-width: 46ch; }

.row {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.row-label {
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--fg-subtle);
}
.chip {
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 8px 14px;
  border: 1px solid var(--glass-border);
  border-radius: 18px;
  background: var(--glass-bg);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: var(--blue);
  cursor: pointer;
  transition: border-color 150ms, background 150ms;
  min-height: 36px;
}
.chip:hover { border-color: var(--blue); background: var(--blue-soft); }
.chip--history { color: var(--fg-muted); }
.chip--history:hover { color: var(--fg); border-color: var(--border-strong); background: var(--surface-2); }

.clear-btn {
  font-size: 12px;
  background: none;
  border: none;
  color: var(--fg-subtle);
  cursor: pointer;
  text-decoration: underline;
  padding: 8px;
}
.clear-btn:hover { color: var(--red); }
</style>
