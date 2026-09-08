<script setup>
defineProps({
  elapsed: { type: Number, default: 0 }
})
</script>

<template>
  <section class="loading" aria-live="polite">
    <p class="loading-status">
      <span class="pulse" aria-hidden="true"></span>
      正在评审 PR…
      <span class="timer">{{ elapsed }}s</span>
      <span class="hint">ReAct Agent 正在阅读 diff，通常需要 10~45 秒</span>
    </p>

    <!-- 骨架屏：预示结果的版面结构，避免信息突然跳入 -->
    <div class="skeleton" aria-hidden="true">
      <div class="sk sk-bar"></div>
      <div class="sk sk-block"></div>
      <div class="sk sk-line" style="width: 90%"></div>
      <div class="sk sk-line" style="width: 72%"></div>
      <div class="sk sk-line" style="width: 84%"></div>
      <div class="sk sk-line" style="width: 60%"></div>
    </div>
  </section>
</template>

<style scoped>
.loading { padding: var(--space-5) 0; }
.loading-status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-muted);
  flex-wrap: wrap;
}
.timer { color: var(--green); font-weight: 600; font-variant-numeric: tabular-nums; min-width: 3ch; }
.hint { color: var(--fg-subtle); font-family: var(--font-sans); font-size: 12px; }

.pulse {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--green);
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }

.skeleton {
  margin-top: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.sk {
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, rgba(100,116,150,0.10) 25%, rgba(100,116,150,0.20) 50%, rgba(100,116,150,0.10) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.6s linear infinite;
}
.sk-bar { height: 64px; border-radius: var(--radius-lg); }
.sk-block { height: 80px; border-radius: var(--radius-lg); margin-top: var(--space-2); }
.sk-line { height: 14px; }
@keyframes shimmer { to { background-position: -200% 0; } }
</style>
