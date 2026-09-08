<script setup>
const props = defineProps({
  counts: { type: Object, default: () => ({}) }
})
const filter = defineModel({ type: String, default: 'ALL' })

const LEVELS = ['ALL', 'HIGH', 'MEDIUM', 'LOW']
function count(l) {
  return l === 'ALL'
    ? props.counts.HIGH + props.counts.MEDIUM + props.counts.LOW
    : props.counts[l]
}
</script>

<template>
  <div class="filter-bar" role="group" aria-label="按风险等级过滤">
    <button
      v-for="l in LEVELS" :key="l"
      class="filter-btn"
      :class="['filter-btn--' + l.toLowerCase(), { 'filter-btn--active': filter === l }]"
      :aria-pressed="filter === l"
      @click="filter = l"
    >
      <span class="f-label">{{ l === 'ALL' ? '全部' : l }}</span>
      <span class="f-count">{{ count(l) }}</span>
    </button>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
  flex-wrap: wrap;
}
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  min-height: 36px;
  padding: 4px 12px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: none;
  color: var(--fg-subtle);
  cursor: pointer;
  transition: color 150ms, border-color 150ms, background 150ms;
}
.filter-btn:hover { color: var(--fg); border-color: var(--border-strong); }
.f-count { font-variant-numeric: tabular-nums; opacity: 0.8; }

/* 激活态按等级着色，与徽标/左边框语义一致 */
.filter-btn--high.filter-btn--active   { color: var(--red);   border-color: var(--red);   background: var(--red-soft); }
.filter-btn--medium.filter-btn--active { color: var(--amber); border-color: var(--amber); background: var(--amber-soft); }
.filter-btn--low.filter-btn--active    { color: var(--blue);  border-color: var(--blue);  background: var(--blue-soft); }
.filter-btn--all.filter-btn--active    { color: var(--green); border-color: var(--green); background: var(--green-soft); }
</style>
