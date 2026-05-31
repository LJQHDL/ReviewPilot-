<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { review, isValidPrUrl } from './api/review.js'
import ResultPanel from './components/ResultPanel.vue'

const prUrl = ref('')
const result = ref(null)
const loading = ref(false)
const errorMsg = ref('')

async function analyze() {
  const url = prUrl.value.trim()
  if (!url) {
    ElMessage.warning('请输入 PR URL')
    return
  }
  if (!isValidPrUrl(url)) {
    ElMessage.warning('URL 格式应为 https://github.com/{owner}/{repo}/pull/{number}')
    return
  }

  loading.value = true
  errorMsg.value = ''
  result.value = null
  try {
    result.value = await review(url)
  } catch (e) {
    errorMsg.value = e.message
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
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
          :disabled="loading"
          @keyup.enter="analyze"
        />
        <el-button
          type="primary"
          style="margin-top: 12px;"
          :loading="loading"
          @click="analyze"
        >
          {{ loading ? '分析中…' : 'Analyze' }}
        </el-button>
      </el-card>

      <el-alert
        v-if="errorMsg"
        :title="errorMsg"
        type="error"
        show-icon
        :closable="false"
        style="margin-top: 16px;"
      />

      <el-card v-if="loading && !result" v-loading="true" style="margin-top: 16px; min-height: 120px;">
        <p style="color: #888; margin: 0;">正在抓取 PR 并交给 DeepSeek 分析，长 PR 可能需要 30~60 秒…</p>
      </el-card>

      <el-card v-if="result" style="margin-top: 16px;">
        <ResultPanel :result="result" />
      </el-card>
    </el-main>
  </el-container>
</template>
