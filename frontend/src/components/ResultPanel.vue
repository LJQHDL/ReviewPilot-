<script setup>
import RiskList from './RiskList.vue'
import SuggestionList from './SuggestionList.vue'

defineProps({
  result: { type: Object, required: true }
})
</script>

<template>
  <div>
    <h3 style="margin: 0 0 8px;">Summary</h3>
    <p v-if="result.summary" style="white-space: pre-wrap; margin: 0 0 20px;">
      {{ result.summary }}
    </p>
    <p v-else style="color: #888; margin: 0 0 20px;">（模型未返回总结）</p>

    <h3 style="margin: 0 0 8px;">Risks</h3>
    <RiskList :risks="result.risks || []" />

    <h3 style="margin: 24px 0 8px;">Suggestions</h3>
    <SuggestionList :suggestions="result.suggestions || []" />

    <div
      v-if="result.meta"
      style="margin-top: 24px; padding-top: 12px; border-top: 1px dashed #e4e7ed; color: #999; font-size: 12px;"
    >
      {{ result.meta.provider }} / {{ result.meta.model }} ·
      {{ result.meta.filesAnalyzed }} files ·
      {{ result.meta.elapsedMs }} ms
    </div>
  </div>
</template>
