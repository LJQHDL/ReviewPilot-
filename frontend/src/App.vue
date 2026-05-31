<script setup>
import { ref } from 'vue'
import { review } from './api/review.js'

const prUrl = ref('')
const result = ref(null)

async function analyze() {
  if (!prUrl.value.trim()) return
  result.value = await review(prUrl.value.trim())
}
</script>

<template>
  <el-container style="padding: 32px; max-width: 960px; margin: 0 auto;">
    <el-header style="padding: 0;">
      <h1 style="margin: 0;">ReviewPilot</h1>
      <p style="color: #888;">AI PR Review 助手 — 输入 GitHub PR URL，一键得到结构化评审结果</p>
    </el-header>

    <el-main style="padding: 24px 0;">
      <el-card>
        <el-input
          v-model="prUrl"
          placeholder="https://github.com/owner/repo/pull/12"
          clearable
          @keyup.enter="analyze"
        />
        <el-button
          type="primary"
          style="margin-top: 12px;"
          @click="analyze"
        >
          Analyze
        </el-button>
      </el-card>

      <el-card v-if="result" style="margin-top: 16px;">
        <pre style="margin: 0; white-space: pre-wrap; word-break: break-word;">{{ JSON.stringify(result, null, 2) }}</pre>
      </el-card>
    </el-main>
  </el-container>
</template>
