<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  risks: { type: Array, default: () => [] },
  filter: { type: String, default: 'ALL' },
  repoUrl: { type: String, default: '' }
})
const emit = defineEmits(['reset-filter'])

const ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2 }

const filtered = computed(() => {
  let list = [...props.risks].sort((a, b) => (ORDER[a.level] ?? 9) - (ORDER[b.level] ?? 9))
  if (props.filter !== 'ALL') list = list.filter(r => r.level === props.filter)
  return list
})

/** 折叠状态用 Set 支持同时展开多条；行内容 id 用于 aria-controls 关联 */
const expanded = ref(new Set())
function toggle(i) {
  const s = new Set(expanded.value)
  s.has(i) ? s.delete(i) : s.add(i)
  expanded.value = s
}

const copied = ref(null)
async function copyRisk(msg, i) {
  try {
    await navigator.clipboard.writeText(msg)
    copied.value = i
    setTimeout(() => { copied.value = null }, 1500)
  } catch { /* 剪贴板不可用时静默降级 */ }
}

/** 折叠态只展示第一句，展开后看全文 */
function preview(msg) {
  if (!msg) return ''
  const dot = msg.indexOf('. ')
  return dot > 40 ? msg.slice(0, dot + 1) : msg
}

function levelOf(risk) { return (risk.level || 'LOW').toLowerCase() }
</script>

<template>
  <div v-if="filtered.length === 0" class="empty">
    <p>没有匹配的风险</p>
    <button v-if="filter !== 'ALL'" class="reset-link" @click="emit('reset-filter')">查看全部</button>
  </div>

  <ul v-else class="list">
    <li v-for="(risk, i) in filtered" :key="i" class="row" :class="'row--' + levelOf(risk)">
      <div class="row-head">
        <!-- 整行折叠区是一个真按钮：键盘可达、有展开状态语义 -->
        <button
          class="row-toggle"
          :aria-expanded="expanded.has(i)"
          :aria-controls="'risk-body-' + i"
          @click="toggle(i)"
        >
          <span class="row-badge" :class="'badge--' + levelOf(risk)">{{ risk.level || 'LOW' }}</span>
          <span class="row-file">
            {{ risk.file || '(全局)' }}<span v-if="risk.line" class="row-line">:{{ risk.line }}</span>
          </span>
          <span class="row-preview">{{ expanded.has(i) ? '' : preview(risk.message) }}</span>
          <svg class="row-chevron" :class="{ 'row-chevron--open': expanded.has(i) }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
        <!-- 复制按钮是兄弟节点而非嵌套按钮，保持键盘可达且获焦时常显 -->
        <button
          class="row-copy"
          :class="{ 'row-copy--done': copied === i }"
          :aria-label="copied === i ? '已复制该风险' : '复制该风险内容'"
          @click="copyRisk(risk.message, i)"
        >
          <svg v-if="copied !== i" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
          </svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </button>
      </div>

      <div :id="'risk-body-' + i" v-show="expanded.has(i)" class="row-body">
        <p class="row-msg">{{ risk.message }}</p>
        <a
          v-if="risk.file && repoUrl"
          class="row-link"
          :href="repoUrl + '/files'"
          target="_blank"
          rel="noopener"
        >在 GitHub 查看该文件 ↗</a>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.list { list-style: none; display: flex; flex-direction: column; }

.empty {
  font-size: 13px;
  color: var(--fg-subtle);
  padding: var(--space-3) 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.reset-link {
  background: none;
  border: none;
  color: var(--blue);
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
  min-height: 36px;
  padding: 0 4px;
}

.row {
  border-left: 3px solid transparent;
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  transition: background 120ms;
}
.row:hover { background: var(--glass-hover); }
.row:focus-within { background: var(--glass-hover); }
.row--high   { border-left-color: var(--red); }
.row--medium { border-left-color: var(--amber); }
.row--low    { border-left-color: var(--blue); }

.row-head {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.row-toggle {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px;
  min-height: 44px;
  background: none;
  border: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.row-badge {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  text-transform: uppercase;
}
.badge--high   { background: var(--red-soft);   color: var(--red); }
.badge--medium { background: var(--amber-soft); color: var(--amber); }
.badge--low    { background: var(--blue-soft);  color: var(--blue); }

.row-file {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 0;
  max-width: 240px;
}
.row-line { color: var(--fg-subtle); }

.row-preview {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-preview:empty { display: none; }

.row-chevron {
  flex-shrink: 0;
  color: var(--fg-subtle);
  transition: transform 150ms;
}
.row-chevron--open { transform: rotate(180deg); }

.row-copy {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  margin-right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  color: var(--fg-subtle);
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 120ms, color 120ms, border-color 120ms;
}
.row-copy:hover, .row-copy:focus-visible { opacity: 1; color: var(--fg); border-color: var(--border); }
.row-head:hover .row-copy { opacity: 0.85; }
.row-copy--done { color: var(--green); opacity: 1; }

.row-body {
  padding: 0 var(--space-3) var(--space-3) calc(10px + 3px);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.row-msg {
  font-size: 14px;
  line-height: 1.65;
  color: var(--fg);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.row-link {
  font-size: 12px;
  color: var(--blue);
  text-decoration: none;
  align-self: flex-start;
  min-height: 32px;
  display: inline-flex;
  align-items: center;
}
.row-link:hover { text-decoration: underline; }

@media (max-width: 640px) {
  .row-file { max-width: 110px; }
  .row-copy { opacity: 0.85; }
}
</style>
