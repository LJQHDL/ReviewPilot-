<script setup>
import { ref } from 'vue'
import axios from 'axios'

const status = ref('未检测')
const detail = ref(null)
const loading = ref(false)

async function ping() {
  loading.value = true
  try {
    const { data } = await axios.get('/api/health')
    status.value = data.status
    detail.value = data
  } catch (e) {
    status.value = 'DOWN'
    detail.value = { error: e.message }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-container style="padding: 32px; max-width: 720px; margin: 0 auto;">
    <el-header style="padding: 0;">
      <h1 style="margin: 0;">ReviewPilot</h1>
      <p style="color: #888;">AI PR Review 助手 · 骨架页（PR#1）</p>
    </el-header>
    <el-main style="padding: 24px 0;">
      <el-card>
        <p>后端连通性自检：</p>
        <el-button type="primary" :loading="loading" @click="ping">Ping /api/health</el-button>
        <el-tag style="margin-left: 12px;" :type="status === 'UP' ? 'success' : status === 'DOWN' ? 'danger' : 'info'">
          {{ status }}
        </el-tag>
        <pre v-if="detail" style="margin-top: 16px; background: #f7f7f7; padding: 12px; border-radius: 4px;">{{ JSON.stringify(detail, null, 2) }}</pre>
      </el-card>
    </el-main>
  </el-container>
</template>
