<script setup>
import { computed } from 'vue'

const props = defineProps({
  risks: { type: Array, default: () => [] }
})

const ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2 }
const TAG_TYPE = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }

const sorted = computed(() =>
  [...props.risks].sort((a, b) => (ORDER[a.level] ?? 9) - (ORDER[b.level] ?? 9))
)
</script>

<template>
  <div v-if="sorted.length === 0" style="color: #888;">未识别到风险点。</div>
  <ul v-else style="list-style: none; padding: 0; margin: 0;">
    <li
      v-for="(risk, i) in sorted"
      :key="i"
      style="padding: 10px 0; border-bottom: 1px solid #f0f0f0;"
    >
      <el-tag :type="TAG_TYPE[risk.level] || 'info'" size="small" effect="dark">
        {{ risk.level }}
      </el-tag>
      <span v-if="risk.file" style="margin-left: 8px; color: #555; font-family: monospace;">
        {{ risk.file }}<span v-if="risk.line">:{{ risk.line }}</span>
      </span>
      <div style="margin-top: 4px;">{{ risk.message }}</div>
    </li>
  </ul>
</template>
