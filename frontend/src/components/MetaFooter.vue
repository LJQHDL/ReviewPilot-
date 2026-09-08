<script setup>
defineProps({
  meta: { type: Object, required: true }
})

function formatMs(ms) {
  if (ms == null) return ''
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}
</script>

<template>
  <footer class="footer-meta">
    <span class="fm-item">
      <span class="fm-k">模型</span>{{ meta.provider }} / {{ meta.model }}
    </span>
    <span class="fm-item" v-if="meta.reactRounds">
      <span class="fm-k">ReAct</span>{{ meta.reactRounds }} 轮 · {{ meta.reactToolCalls || 0 }} 次工具调用
    </span>
    <span class="fm-item" v-if="meta.agentRounds > 1">
      <span class="fm-k">质检</span>修订 {{ meta.agentRounds - 1 }} 次
    </span>
    <span class="fm-item" v-if="meta.promptTokens">
      <span class="fm-k">Tokens</span>{{ meta.promptTokens }} + {{ meta.completionTokens }}
    </span>
    <span class="fm-item">
      <span class="fm-k">耗时</span>{{ formatMs(meta.elapsedMs) }}
    </span>
  </footer>
</template>

<style scoped>
.footer-meta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-subtle);
  padding: var(--space-4) 0 var(--space-2);
  border-top: 1px solid var(--border);
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}
.fm-item { display: inline-flex; align-items: baseline; gap: 6px; font-variant-numeric: tabular-nums; }
.fm-k {
  color: var(--fg-muted);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 1px 5px;
}
</style>
