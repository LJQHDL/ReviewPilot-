<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { review, isValidPrUrl } from './api/review.js'
import RiskList from './components/RiskList.vue'
import SuggestionList from './components/SuggestionList.vue'

const prUrl = ref('')
const result = ref(null)
const loading = ref(false)
const errorMsg = ref('')
const riskFilter = ref('ALL')

// ── Honest elapsed timer during loading ──
const elapsed = ref(0)
let elapsedTimer = null
function startTimer() { elapsed.value = 0; elapsedTimer = setInterval(() => elapsed.value++, 1000) }
function stopTimer() { clearInterval(elapsedTimer) }

const examples = [
  { label: 'Dubbo #16345', url: 'https://github.com/apache/dubbo/pull/16345' },
  { label: 'Spring Boot #30000', url: 'https://github.com/spring-projects/spring-boot/pull/30000' },
  { label: 'Hello World #1', url: 'https://github.com/octocat/Hello-World/pull/1' },
]

function tryExample(url) {
  prUrl.value = url
  analyze()
}

// ── Parse PR info from URL ──
const prInfo = computed(() => {
  if (!result.value?.prUrl) return null
  const m = result.value.prUrl.match(/github\.com\/([^/]+)\/([^/]+)\/pull\/(\d+)/)
  if (!m) return null
  return { owner: m[1], repo: m[2], number: m[3] }
})

// ── Verdict ──
const verdict = computed(() => {
  if (!result.value) return null
  const risks = result.value.risks || []
  const high = risks.filter(r => r.level === 'HIGH').length
  const medium = risks.filter(r => r.level === 'MEDIUM').length
  const low = risks.filter(r => r.level === 'LOW').length
  const total = risks.length
  if (total === 0) return { grade: 'PASS', label: 'Looks Good', desc: 'No risks detected', high, medium, low }
  if (high >= 3) return { grade: 'FAIL', label: 'Request Changes', desc: `${high} HIGH severity issues — must fix before merge`, high, medium, low }
  if (high >= 1) return { grade: 'WARN', label: 'Review Required', desc: `${high} HIGH severity issue${high > 1 ? 's' : ''} needs attention`, high, medium, low }
  return { grade: 'OK', label: 'Approve with Comments', desc: `${medium} MEDIUM issues — review at your discretion`, high, medium, low }
})

// ── Key points from backend ──
const keyPoints = computed(() => result.value?.keyFindings || [])

// ── Analyze ──
async function analyze() {
  const url = prUrl.value.trim()
  if (!url) { ElMessage.warning('Please enter a PR URL'); return }
  if (!isValidPrUrl(url)) {
    ElMessage.warning('URL format: https://github.com/{owner}/{repo}/pull/{number}')
    return
  }
  loading.value = true; errorMsg.value = ''; result.value = null
  startTimer()
  try { result.value = await review(url) }
  catch (e) { errorMsg.value = e.message; ElMessage.error(e.message) }
  finally { stopTimer(); loading.value = false }
}

function formatMs(ms) {
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(1) + 's'
}
</script>

<template>
  <div class="app">
    <!-- Top Bar -->
    <header class="topbar">
      <div class="topbar-brand">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          <polyline points="8 11 11 14 15 9"/>
        </svg>
        <span>ReviewPilot</span>
      </div>
      <form class="topbar-input" @submit.prevent="analyze">
        <input id="pr-url" v-model="prUrl" class="url-input" placeholder="github.com/owner/repo/pull/12" :disabled="loading" />
        <button class="btn-go" :disabled="loading">
          <svg v-if="!loading" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>
          <span v-else class="mini-spinner"></span>
        </button>
      </form>
    </header>

    <!-- Content -->
    <div class="content">

      <!-- Loading (honest — no fake stages) -->
      <div v-if="loading" class="loading-block">
        <span class="loading-pulse"></span> Analyzing PR… <span class="loading-timer">{{ elapsed }}s</span>
      </div>

      <!-- Error -->
      <div v-if="errorMsg" class="error-banner">{{ errorMsg }}</div>

      <!-- Empty -->
      <div v-if="!result && !loading && !errorMsg" class="empty-state">
        <p class="empty-title">Paste a GitHub PR URL to start</p>
        <p class="empty-desc">The ReAct agent autonomously explores the diff, fetches context, and produces a structured review.</p>
        <div class="examples">
          <span class="examples-label">Try an example:</span>
          <button v-for="ex in examples" :key="ex.url" class="example-btn" @click="tryExample(ex.url)">{{ ex.label }}</button>
        </div>
      </div>

      <!-- ============ RESULT ============ -->
      <div v-if="result && !loading" class="result">

        <!-- 1. PR Context -->
        <div v-if="prInfo" class="pr-ctx">
          <a :href="result.prUrl" target="_blank" class="pr-link">
            {{ prInfo.owner }}/<b>{{ prInfo.repo }}</b>#{{ prInfo.number }}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="7" y1="17" x2="17" y2="7"/><polyline points="7 7 17 7 17 17"/></svg>
          </a>
          <span v-if="result.meta" class="pr-meta">{{ result.meta.filesAnalyzed }} files</span>
        </div>

        <!-- 2. Verdict Banner -->
        <div v-if="verdict" class="verdict" :class="'verdict--' + verdict.grade">
          <div class="verdict-left">
            <span class="verdict-grade">{{ verdict.label }}</span>
            <span class="verdict-desc">{{ verdict.desc }}</span>
          </div>
          <div class="verdict-counts">
            <span v-if="verdict.high" class="vc vc--high">{{ verdict.high }} HIGH</span>
            <span v-if="verdict.medium" class="vc vc--medium">{{ verdict.medium }} MEDIUM</span>
            <span v-if="verdict.low" class="vc vc--low">{{ verdict.low }} LOW</span>
          </div>
        </div>

        <!-- 3. Key Points -->
        <div v-if="keyPoints.length" class="keypoints">
          <div class="section-label">Key Findings</div>
          <ul>
            <li v-for="(p, i) in keyPoints" :key="i">{{ p }}</li>
          </ul>
        </div>

        <!-- 4. Risk List -->
        <div class="section">
          <div class="section-label">Risks <span class="badge">{{ (result.risks || []).length }}</span></div>
          <div class="filter-bar">
            <button v-for="l in ['ALL','HIGH','MEDIUM','LOW']" :key="l"
              class="filter-btn" :class="{ 'filter-btn--active': riskFilter === l }"
              @click="riskFilter = l">{{ l === 'ALL' ? 'All' : l }}</button>
          </div>
          <RiskList :risks="result.risks || []" :filter="riskFilter" :repo-url="result.prUrl || ''" />
        </div>

        <!-- 5. Suggestions -->
        <div v-if="(result.suggestions || []).length" class="section">
          <div class="section-label">Suggestions <span class="badge">{{ (result.suggestions || []).length }}</span></div>
          <SuggestionList :suggestions="result.suggestions || []" />
        </div>

        <!-- 6. Footer -->
        <div v-if="result.meta" class="footer-meta">
          {{ result.meta.provider }} / {{ result.meta.model }}
          <span class="meta-sep">·</span>
          <span v-if="result.meta.reactRounds">{{ result.meta.reactRounds }} ReAct round{{ result.meta.reactRounds > 1 ? 's' : '' }}</span>
          <span v-if="result.meta.reactToolCalls" class="meta-sep">·</span>
          <span v-if="result.meta.reactToolCalls">{{ result.meta.reactToolCalls }} tool call{{ result.meta.reactToolCalls > 1 ? 's' : '' }}</span>
          <span v-if="result.meta.agentRounds > 1" class="meta-sep">·</span>
          <span v-if="result.meta.agentRounds > 1">{{ result.meta.agentRounds }} review pass{{ result.meta.agentRounds > 1 ? 'es' : '' }}</span>
          <span class="meta-sep">·</span>
          {{ formatMs(result.meta.elapsedMs) }}
        </div>

      </div>
    </div>
  </div>
</template>

<style>
/* ═══════════════════════════════════════════════
   ReviewPilot — Decision-First Layout
   ═══════════════════════════════════════════════ */

/* ── Reset & Tokens ── */
:root {
  --bg:        #0D1117;
  --bg-raised: #161B22;
  --border:    #30363D;
  --border-h:  #484F58;
  --text:      #E6EDF3;
  --text-2:    #8B949E;
  --text-3:    #6E7681;
  --green:     #3FB950;
  --green-m:   rgba(63,185,80,0.12);
  --red:       #F85149;
  --red-m:     rgba(248,81,73,0.10);
  --amber:     #D29922;
  --amber-m:   rgba(210,153,34,0.10);
  --blue:      #58A6FF;
  --blue-m:    rgba(88,166,255,0.08);
  --font:      'Fira Sans', 'Inter', system-ui, sans-serif;
  --mono:      'Fira Code', 'Cascadia Code', monospace;
  --radius:    6px;
}

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { font-size: 15px; -webkit-font-smoothing: antialiased; }
body { font-family: var(--font); background: var(--bg); color: var(--text); line-height: 1.5; }
:focus-visible { outline: 2px solid var(--green); outline-offset: 1px; }
::-webkit-scrollbar { width: 5px; }
::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
}

.app { min-height: 100vh; display: flex; flex-direction: column; background: var(--bg); }

/* ── Top Bar ── */
.topbar {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 16px; background: var(--bg-raised);
  border-bottom: 1px solid var(--border); min-height: 40px;
}
.topbar-brand {
  display: flex; align-items: center; gap: 6px;
  font-family: var(--mono); font-size: 12px; font-weight: 600;
  color: var(--text); white-space: nowrap;
}
.topbar-brand svg { color: var(--green); }
.topbar-input { flex: 1; display: flex; max-width: 560px; margin-left: auto; }
.url-input {
  flex: 1; height: 28px; padding: 0 10px;
  background: var(--bg); border: 1px solid var(--border); border-right: none;
  border-radius: 4px 0 0 4px; color: var(--text);
  font-family: var(--mono); font-size: 11px; outline: none;
}
.url-input:focus { border-color: var(--blue); }
.url-input::placeholder { color: var(--text-3); }
.btn-go {
  height: 28px; width: 32px; display: flex; align-items: center; justify-content: center;
  background: var(--green); border: 1px solid var(--green);
  border-radius: 0 4px 4px 0; color: var(--bg); cursor: pointer;
}
.btn-go:disabled { opacity: 0.5; cursor: not-allowed; }
.mini-spinner { width: 12px; height: 12px; border: 2px solid var(--bg); border-top-color: transparent; border-radius: 50%; animation: spin 0.5s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── Content ── */
.content {
  flex: 1; max-width: 780px; width: 100%; margin: 0 auto; padding: 20px 16px;
}
.loading-block {
  display: flex; align-items: center; gap: 10px; padding: 24px 0;
  font-family: var(--mono); font-size: 13px; color: var(--text-2);
}
.loading-timer { color: var(--green); font-weight: 600; min-width: 32px; }
.loading-pulse { width: 8px; height: 8px; border-radius: 50%; background: var(--green); animation: pulse 1s infinite; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
.error-banner {
  padding: 8px 12px; background: var(--red-m); border: 1px solid var(--red);
  border-radius: var(--radius); color: var(--red); font-size: 12px; font-family: var(--mono);
}
.empty-state { text-align: center; padding: 48px 0; color: var(--text-2); }
.empty-title { font-size: 15px; margin-bottom: 6px; }
.empty-desc { font-size: 12px; color: var(--text-3); }
.examples { display: flex; align-items: center; gap: 8px; margin-top: 16px; justify-content: center; flex-wrap: wrap; }
.examples-label { font-size: 11px; color: var(--text-3); font-family: var(--mono); }
.example-btn {
  font-family: var(--mono); font-size: 11px;
  padding: 4px 12px; border: 1px solid var(--border); border-radius: 14px;
  background: none; color: var(--blue); cursor: pointer;
  transition: all 120ms;
}
.example-btn:hover { border-color: var(--blue); background: var(--blue-m); }

/* ── Result ── */
.result { display: flex; flex-direction: column; gap: 14px; }

/* PR Context */
.pr-ctx {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; background: var(--bg-raised);
  border: 1px solid var(--border); border-radius: var(--radius);
}
.pr-link {
  font-family: var(--mono); font-size: 13px; color: var(--blue);
  text-decoration: none; display: flex; align-items: center; gap: 4px;
}
.pr-link:hover { text-decoration: underline; }
.pr-link b { color: var(--text); }
.pr-meta { font-size: 12px; color: var(--text-3); margin-left: auto; }

/* ── Verdict Banner ── */
.verdict {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; border-radius: var(--radius); border: 1px solid;
}
.verdict--PASS { background: rgba(63,185,80,0.08); border-color: var(--green); }
.verdict--OK   { background: rgba(88,166,255,0.06); border-color: var(--blue); }
.verdict--WARN { background: var(--amber-m); border-color: var(--amber); }
.verdict--FAIL { background: var(--red-m); border-color: var(--red); }

.verdict-left { display: flex; flex-direction: column; gap: 2px; }
.verdict-grade { font-size: 18px; font-weight: 700; }
.verdict--PASS .verdict-grade { color: var(--green); }
.verdict--OK   .verdict-grade { color: var(--blue); }
.verdict--WARN .verdict-grade { color: var(--amber); }
.verdict--FAIL .verdict-grade { color: var(--red); }

.verdict-desc { font-size: 13px; color: var(--text-2); }

.verdict-counts { display: flex; gap: 10px; }
.vc { font-family: var(--mono); font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 3px; }
.vc--high   { background: var(--red-m); color: var(--red); }
.vc--medium { background: var(--amber-m); color: var(--amber); }
.vc--low    { background: var(--blue-m); color: var(--blue); }

/* ── Key Points ── */
.keypoints {
  padding: 14px 16px; background: var(--bg-raised);
  border: 1px solid var(--border); border-radius: var(--radius);
}
.keypoints ul { list-style: none; display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.keypoints li {
  font-size: 13px; color: var(--text); line-height: 1.55;
  padding-left: 14px; position: relative;
}
.keypoints li::before {
  content: ''; position: absolute; left: 0; top: 7px;
  width: 5px; height: 5px; border-radius: 50%; background: var(--green);
}

/* ── Section ── */
.section { }
.section-label {
  font-family: var(--mono); font-size: 11px; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.6px;
  color: var(--text-2); margin-bottom: 6px;
  display: flex; align-items: center; gap: 6px;
}
.badge { font-size: 10px; background: var(--border); color: var(--text-3); padding: 1px 6px; border-radius: 8px; }

/* Filter */
.filter-bar { display: flex; gap: 4px; margin-bottom: 8px; }
.filter-btn {
  font-family: var(--mono); font-size: 10px; font-weight: 500;
  padding: 2px 10px; border: 1px solid var(--border); border-radius: 12px;
  background: none; color: var(--text-3); cursor: pointer;
  transition: all 120ms;
}
.filter-btn:hover { color: var(--text); border-color: var(--border-h); }
.filter-btn--active { color: var(--green); border-color: var(--green); background: var(--green-m); }

/* Footer */
.footer-meta {
  font-family: var(--mono); font-size: 11px; color: var(--text-3);
  padding: 16px 0 8px; border-top: 1px solid var(--border);
  display: flex; align-items: center; gap: 6px;
}
.meta-sep { color: var(--border); }

/* ── Responsive ── */

/* ≥1280px — wider content, more breathing room */
@media (min-width: 1280px) {
  .content { max-width: 960px; }
}

/* 768–1024px — tablet landscape, compact but not cramped */
@media (max-width: 1024px) {
  .content { max-width: 680px; }
}

/* ≤768px — tablet portrait, single column */
@media (max-width: 768px) {
  .content { max-width: 100%; padding: 14px 12px; }
  .verdict { flex-direction: column; align-items: flex-start; gap: 8px; }
  .verdict-grade { font-size: 16px; }
  .verdict-counts { align-self: flex-start; }
  .topbar-input { max-width: 100%; }
}

/* ≤640px — phone, stack everything */
@media (max-width: 640px) {
  .topbar { flex-wrap: wrap; gap: 6px; padding: 6px 10px; }
}
</style>
