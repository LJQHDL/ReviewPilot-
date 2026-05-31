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
  <div v-if="grouped.length === 0" style="color: #888;">没有建议。</div>
  <div v-else>
    <div
      v-for="group in grouped"
      :key="group.file"
      style="margin-bottom: 16px;"
    >
      <div style="font-family: monospace; color: #555; margin-bottom: 6px;">
        {{ group.file }}
      </div>
      <ul style="list-style: disc; padding-left: 20px; margin: 0;">
        <li v-for="(s, i) in group.items" :key="i" style="margin-bottom: 4px;">
          <span v-if="s.line" style="color: #888;">L{{ s.line }} — </span>
          {{ s.message }}
        </li>
      </ul>
    </div>
  </div>
</template>
