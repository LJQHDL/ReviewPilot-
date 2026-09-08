<script setup>
defineProps({
  message: { type: String, required: true },
  prUrl: { type: String, default: '' }
})
defineEmits(['retry'])
</script>

<template>
  <section class="error-panel glass" role="alert">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
    <div class="error-body">
      <p class="error-title">评审失败</p>
      <p class="error-msg">{{ message }}</p>
      <div class="error-actions">
        <button class="btn btn--retry" @click="$emit('retry')">重试</button>
        <a v-if="prUrl" :href="prUrl" target="_blank" rel="noopener" class="btn btn--link">在 GitHub 查看 PR ↗</a>
      </div>
    </div>
  </section>
</template>

<style scoped>
.error-panel {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  background: linear-gradient(135deg, var(--red-soft), transparent 65%), var(--glass-bg);
  border-color: rgba(225, 29, 72, 0.45);
  border-radius: var(--radius-lg);
}
.error-panel svg { color: var(--red); flex-shrink: 0; margin-top: 2px; }
.error-body { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
.error-title { font-weight: 600; color: var(--red); font-size: 14px; }
.error-msg {
  font-size: 13px;
  color: var(--fg);
  font-family: var(--font-mono);
  word-break: break-word;
}
.error-actions { display: flex; gap: var(--space-3); margin-top: var(--space-1); }
.btn {
  display: inline-flex;
  align-items: center;
  min-height: 36px;
  padding: 4px 14px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: background 150ms, border-color 150ms;
}
.btn--retry {
  background: var(--red);
  border: 1px solid rgba(255, 255, 255, 0.4);
  color: #FFFFFF;
}
.btn--retry:hover { filter: brightness(1.1); }
.btn--link {
  background: none;
  border: 1px solid var(--border-strong);
  color: var(--fg-muted);
}
.btn--link:hover { border-color: var(--blue); color: var(--blue); }
</style>
