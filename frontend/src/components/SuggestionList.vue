<script setup>
import { computed } from 'vue'

const props = defineProps({
  suggestions: { type: Array, default: () => [] }
})

const grouped = computed(() => {
  const map = new Map()
  for (const s of props.suggestions) {
    const key = s.file || '(general)'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(s)
  }
  return [...map.entries()].map(([file, items]) => ({ file, items }))
})
</script>

<template>
  <div class="sug-list">
    <div v-for="(group, gi) in grouped" :key="gi" class="sug-group">
      <div class="sug-file">{{ group.file }}</div>
      <div v-for="(s, si) in group.items" :key="si" class="sug-row">
        <span v-if="s.line" class="sug-ln">L{{ s.line }}</span>
        <span>{{ s.message }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sug-list { display: flex; flex-direction: column; gap: 10px; }

.sug-file {
  font-family: var(--mono); font-size: 11px; font-weight: 600;
  color: var(--blue); padding-bottom: 4px; margin-bottom: 4px;
  border-bottom: 1px solid var(--border);
}

.sug-row {
  display: flex; gap: 8px; padding: 3px 0 3px 8px;
  font-size: 13px; line-height: 1.5; color: var(--text);
  border-left: 2px solid transparent;
}
.sug-row:hover { border-left-color: var(--green); }

.sug-ln {
  font-family: var(--mono); font-size: 11px;
  color: var(--green); flex-shrink: 0; min-width: 22px;
}
</style>
