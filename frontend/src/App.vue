<script setup>
import { computed, onMounted, ref } from 'vue'
import { review, isValidPrUrl, normalizePrUrl } from './api/review.js'
import AppHeader from './components/AppHeader.vue'
import EmptyState from './components/EmptyState.vue'
import LoadingPanel from './components/LoadingPanel.vue'
import ErrorPanel from './components/ErrorPanel.vue'
import VerdictBanner from './components/VerdictBanner.vue'
import KeyFindings from './components/KeyFindings.vue'
import RiskFilterBar from './components/RiskFilterBar.vue'
import RiskList from './components/RiskList.vue'
import SuggestionList from './components/SuggestionList.vue'
import MetaFooter from './components/MetaFooter.vue'

const prUrl = ref('')
const status = ref('idle') // idle | loading | error | done
const result = ref(null)
const errorMsg = ref('')
const fieldError = ref('')
const riskFilter = ref('ALL')

const HISTORY_KEY = 'reviewpilot.history'
const MAX_HISTORY = 5
const history = ref(loadHistory())

function loadHistory() {
  try {
    const raw = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(raw) ? raw.slice(0, MAX_HISTORY) : []
  } catch { return [] }
}
function pushHistory(url) {
  const info = parsePr(url)
  if (!info) return
  const label = `${info.owner}/${info.repo}#${info.number}`
  const rest = history.value.filter(h => h.url !== url)
  history.value = [{ url, label }, ...rest].slice(0, MAX_HISTORY)
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value)) } catch { /* 隐私模式下忽略 */ }
}
function clearHistory() {
  history.value = []
  try { localStorage.removeItem(HISTORY_KEY) } catch { /* ignore */ }
}

const examples = [
  { label: 'Dubbo #16345', url: 'https://github.com/apache/dubbo/pull/16345' },
  { label: 'Spring Boot #30000', url: 'https://github.com/spring-projects/spring-boot/pull/30000' },
  { label: 'Hello World #1', url: 'https://github.com/octocat/Hello-World/pull/1' },
]

// ── 诚实的耗时计时器：不编造进度阶段 ──
const elapsed = ref(0)
let elapsedTimer = null
function startTimer() { elapsed.value = 0; elapsedTimer = setInterval(() => elapsed.value++, 1000) }
function stopTimer() { clearInterval(elapsedTimer) }

function parsePr(url) {
  const m = url.match(/github\.com\/([^/]+)\/([^/]+)\/pull\/(\d+)/)
  if (!m) return null
  return { owner: m[1], repo: m[2], number: m[3] }
}

const prInfo = computed(() => result.value ? parsePr(result.value.prUrl || '') : null)

const counts = computed(() => {
  const risks = result.value?.risks || []
  return {
    HIGH: risks.filter(r => r.level === 'HIGH').length,
    MEDIUM: risks.filter(r => r.level === 'MEDIUM').length,
    LOW: risks.filter(r => r.level === 'LOW').length
  }
})

const verdict = computed(() => {
  if (!result.value) return null
  const { HIGH: high, MEDIUM: medium, LOW: low } = counts.value
  const total = high + medium + low
  if (total === 0) return { grade: 'PASS', label: '评审通过', desc: '未检测到风险，可以合并', high, medium, low }
  if (high >= 3) return { grade: 'FAIL', label: '建议修改后合并', desc: `${high} 条 HIGH 级问题——合并前必须处理`, high, medium, low }
  if (high >= 1) return { grade: 'WARN', label: '需要关注', desc: `存在 ${high} 条 HIGH 级问题，请逐条确认`, high, medium, low }
  return { grade: 'OK', label: '可合并（附意见）', desc: `${medium} 条 MEDIUM 级问题，酌情处理`, high, medium, low }
})

const keyPoints = computed(() => result.value?.keyFindings || [])

/** 提交入口：归一化 URL → 本地校验 → 调用后端 */
async function start(urlInput) {
  const url = normalizePrUrl(urlInput ?? prUrl.value)
  fieldError.value = ''
  if (!url) { fieldError.value = '请输入 GitHub PR 地址'; return }
  if (!isValidPrUrl(url)) {
    fieldError.value = '地址格式应为 https://github.com/所有者/仓库/pull/编号'
    return
  }
  prUrl.value = url
  status.value = 'loading'
  errorMsg.value = ''
  result.value = null
  riskFilter.value = 'ALL'
  startTimer()
  try {
    result.value = await review(url)
    status.value = 'done'
    pushHistory(url)
    // 深链：把 PR 地址同步到查询串，方便分享与刷新恢复
    syncDeepLink(url)
  } catch (e) {
    errorMsg.value = e.message
    status.value = 'error'
  } finally {
    stopTimer()
  }
}

function syncDeepLink(url) {
  const q = new URLSearchParams({ pr: url })
  window.history.replaceState(null, '', `?${q}`)
}

function selectExample(url) {
  prUrl.value = url
  start(url)
}

// 深链进入：?pr=... 有效时自动开始评审
onMounted(() => {
  const q = new URLSearchParams(window.location.search).get('pr')
  if (q) {
    const url = normalizePrUrl(q)
    if (isValidPrUrl(url)) { prUrl.value = url; start(url) }
  }
})
</script>

<template>
  <div class="app">
    <!-- 极光背景层：为玻璃面板提供可折射的色彩 -->
    <div class="bg-decor" aria-hidden="true">
      <span class="blob blob--violet"></span>
      <span class="blob blob--cyan"></span>
      <span class="blob blob--pink"></span>
    </div>

    <AppHeader v-model="prUrl" :loading="status === 'loading'" :field-error="fieldError" @submit="start()" />

    <main class="content">
      <LoadingPanel v-if="status === 'loading'" :elapsed="elapsed" />

      <ErrorPanel v-else-if="status === 'error'" :message="errorMsg" :pr-url="prUrl" @retry="start()" />

      <EmptyState
        v-else-if="status === 'idle'"
        :examples="examples"
        :history="history"
        @select="selectExample"
        @clear-history="clearHistory"
      />

      <div v-else-if="result" class="result">
        <!-- PR 上下文条 -->
        <div v-if="prInfo" class="pr-ctx glass">
          <a :href="result.prUrl" target="_blank" rel="noopener" class="pr-link">
            {{ prInfo.owner }}/<b>{{ prInfo.repo }}</b>#{{ prInfo.number }}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="7" y1="17" x2="17" y2="7"/><polyline points="7 7 17 7 17 17"/></svg>
          </a>
          <span v-if="result.meta" class="pr-meta">{{ result.meta.filesAnalyzed }} 个变更文件</span>
          <span v-if="result.meta?.filesTruncated" class="pr-truncated" title="变更文件数超出抓取上限，本报告只覆盖部分文件">⚠ 部分文件未覆盖</span>
        </div>

        <VerdictBanner v-if="verdict" :verdict="verdict" />

        <KeyFindings :items="keyPoints" />

        <!-- 风险区：过滤条 + 列表 -->
        <section class="section" aria-labelledby="risks-heading">
          <h3 id="risks-heading" class="section-label">
            风险 <span class="badge">{{ (result.risks || []).length }}</span>
          </h3>
          <RiskFilterBar v-model="riskFilter" :counts="counts" />
          <RiskList :risks="result.risks || []" :filter="riskFilter" :repo-url="result.prUrl || ''" @reset-filter="riskFilter = 'ALL'" />
        </section>

        <section v-if="(result.suggestions || []).length" class="section" aria-labelledby="sug-heading">
          <h3 id="sug-heading" class="section-label">
            建议 <span class="badge">{{ (result.suggestions || []).length }}</span>
          </h3>
          <SuggestionList :suggestions="result.suggestions || []" />
        </section>

        <MetaFooter v-if="result.meta" :meta="result.meta" />
      </div>
    </main>
  </div>
</template>

<style>
.app { min-height: 100vh; min-height: 100dvh; display: flex; flex-direction: column; position: relative; }
/* 内容全部抬到极光层之上 */
.topbar, .content { position: relative; z-index: 1; }

.content {
  flex: 1;
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: var(--space-5) var(--space-4);
}

.result { display: flex; flex-direction: column; gap: var(--space-4); }

.pr-ctx {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 12px var(--space-4);
  border-radius: var(--radius);
  flex-wrap: wrap;
}
.pr-link {
  font-family: var(--font-mono);
  font-size: 14px;
  color: var(--blue);
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 32px;
}
.pr-link:hover { text-decoration: underline; }
.pr-link b { color: var(--fg); }
.pr-meta { font-size: 13px; color: var(--fg-subtle); margin-left: auto; }
.pr-truncated {
  font-size: 12px;
  color: var(--amber);
  border: 1px solid var(--amber);
  border-radius: 14px;
  padding: 3px 12px;
}

.badge {
  font-size: 11px;
  background: var(--surface-2);
  color: var(--fg-muted);
  padding: 2px 9px;
  border-radius: 10px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 640px) {
  .content { padding: var(--space-4) var(--space-3); }
  .pr-meta { margin-left: 0; width: 100%; }
}
</style>
