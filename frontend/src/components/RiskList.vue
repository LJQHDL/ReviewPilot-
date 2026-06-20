<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  risks: { type: Array, default: () => [] },
  filter: { type: String, default: 'ALL' },
  repoUrl: { type: String, default: '' }
})

const ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2 }

const filtered = computed(() => {
  let list = [...props.risks].sort((a, b) => (ORDER[a.level] ?? 9) - (ORDER[b.level] ?? 9))
  if (props.filter !== 'ALL') list = list.filter(r => r.level === props.filter)
  return list
})

const expanded = ref(null)
const copied = ref(null)
function toggle(i) { expanded.value = expanded.value === i ? null : i }

async function copyRisk(msg, i) {
  try {
    await navigator.clipboard.writeText(msg)
    copied.value = i
    setTimeout(() => { copied.value = null }, 1500)
  } catch { /* clipboard not available */ }
}

// Extract first sentence for preview
function preview(msg) {
  const dot = msg.indexOf('. ')
  return dot > 30 ? msg.slice(0, dot + 1) : msg
}
</script>

<template>
  <div v-if="filtered.length === 0" class="empty">No matching risks</div>
  <div v-else class="list">
    <div v-for="(risk, i) in filtered" :key="i" class="row" :class="'row--' + (risk.level || 'LOW').toLowerCase()">
      <!-- Header: always visible -->
      <div class="row-top" @click="toggle(i)">
        <span class="row-badge" :class="'badge--' + (risk.level || 'LOW').toLowerCase()">{{ risk.level || 'LOW' }}</span>
        <a v-if="risk.file && repoUrl" :href="repoUrl + '/files'" target="_blank" class="row-file" :title="risk.file + (risk.line ? ':' + risk.line : '')">{{ risk.file }}<span v-if="risk.line" class="row-line">:{{ risk.line }}</span></a>
        <span v-else-if="risk.file" class="row-file">{{ risk.file }}<span v-if="risk.line" class="row-line">:{{ risk.line }}</span></span>
        <span class="row-preview">{{ expanded === i ? '' : preview(risk.message) }}</span>
        <svg class="row-chevron" :class="{ 'row-chevron--open': expanded === i }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
        <button class="row-copy" :class="{ 'row-copy--done': copied === i }" @click.stop="copyRisk(risk.message, i)" :title="copied === i ? 'Copied' : 'Copy'">
          <svg v-if="copied !== i" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
          </svg>
          <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </button>
      </div>

      <!-- Body: expanded detail -->
      <div v-if="expanded === i" class="row-body">
        <p class="row-msg">{{ risk.message }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.empty { font-size: 12px; color: var(--text-3); padding: 8px 0; }

/* ── List ── */
.list { display: flex; flex-direction: column; }

/* ── Row ── */
.row {
  border-left: 3px solid transparent;
  transition: background 120ms;
}
.row:hover { background: var(--bg-raised); }
.row--high   { border-left-color: var(--red); }
.row--medium { border-left-color: var(--amber); }
.row--low    { border-left-color: var(--blue); }

.row-top {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; cursor: pointer;
  min-height: 36px;
}

/* Badge */
.row-badge {
  font-family: var(--mono); font-size: 9px; font-weight: 700;
  letter-spacing: 0.4px; padding: 1px 6px; border-radius: 3px;
  flex-shrink: 0; text-transform: uppercase;
}
.badge--high   { background: var(--red-m); color: var(--red); }
.badge--medium { background: var(--amber-m); color: var(--amber); }
.badge--low    { background: var(--blue-m); color: var(--blue); }

/* File */
.row-file {
  font-family: var(--mono); font-size: 11px; color: var(--text-2);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  flex-shrink: 0; max-width: 220px;
  text-decoration: none;
}
a.row-file:hover { color: var(--blue); text-decoration: underline; }
.row-line { color: var(--text-3); }

/* Preview */
.row-preview {
  flex: 1; font-size: 12px; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}

/* Chevron */
.row-chevron {
  flex-shrink: 0; color: var(--text-3);
  transition: transform 150ms;
}
.row-chevron--open { transform: rotate(180deg); }

/* Copy button */
.row-copy {
  flex-shrink: 0; width: 24px; height: 24px;
  display: flex; align-items: center; justify-content: center;
  background: none; border: 1px solid transparent; border-radius: 4px;
  color: var(--text-3); cursor: pointer;
  opacity: 0; transition: opacity 120ms, color 120ms, border-color 120ms;
}
.row:hover .row-copy { opacity: 1; }
.row-copy:hover { color: var(--text); border-color: var(--border); }
.row-copy--done { color: var(--green); opacity: 1; }

/* Body */
.row-body {
  padding: 0 10px 10px 10px;
  padding-left: calc(10px + 60px); /* align after badge+file */
}
.row-msg {
  font-size: 13px; line-height: 1.6; color: var(--text);
  white-space: pre-wrap; word-break: break-word;
}

@media (max-width: 640px) {
  .row-file { max-width: 120px; }
  .row-body { padding-left: 10px; }
}
</style>
