<script setup>
import { computed } from 'vue'

const props = defineProps({
  suggestions: { type: Array, default: () => [] }
})

/** 同一文件的建议聚成一组，避免重复文件名造成的视觉噪音 */
const grouped = computed(() => {
  const map = new Map()
  for (const s of props.suggestions) {
    const key = s.file || '(通用建议)'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(s)
  }
  return [...map.entries()].map(([file, items]) => ({ file, items }))
})
</script>

<template>
  <div class="sug-list">
    <div v-for="(group, gi) in grouped" :key="gi" class="sug-group">
      <h4 class="sug-file">{{ group.file }}</h4>
      <ul>
        <li v-for="(s, si) in group.items" :key="si" class="sug-row">
          <span v-if="s.line" class="sug-ln">L{{ s.line }}</span>
          <span class="sug-msg">{{ s.message }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.sug-list { display: flex; flex-direction: column; gap: var(--space-3); }

.sug-group ul { list-style: none; }
.sug-file {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--blue);
  padding: var(--space-1) 0;
  margin-bottom: var(--space-1);
  border-bottom: 1px solid var(--border);
  overflow-wrap: anywhere;
}

.sug-row {
  display: flex;
  gap: var(--space-2);
  padding: 6px 0 6px var(--space-2);
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg);
  border-left: 2px solid transparent;
  transition: border-color 120ms;
}
.sug-row:hover { border-left-color: var(--green); }

.sug-ln {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--green);
  flex-shrink: 0;
  min-width: 34px;
  font-variant-numeric: tabular-nums;
}
.sug-msg { min-width: 0; overflow-wrap: anywhere; }
</style>
