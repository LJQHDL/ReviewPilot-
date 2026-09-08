<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  loading: { type: Boolean, default: false },
  fieldError: { type: String, default: '' }
})
const emit = defineEmits(['submit'])

// v-model 由父组件持有输入值，便于深链与历史记录回填
const url = defineModel({ type: String, default: '' })
const inputRef = ref(null)

/** 全局快捷键：按 / 聚焦搜索框（正在输入时不拦截） */
function onGlobalKey(e) {
  if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return
  const el = document.activeElement
  const typing = el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)
  if (typing) return
  e.preventDefault()
  inputRef.value?.focus()
}
onMounted(() => document.addEventListener('keydown', onGlobalKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKey))
</script>

<template>
  <header class="topbar glass">
    <div class="topbar-inner">
      <div class="brand">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          <polyline points="8 11 11 14 15 9"/>
        </svg>
        <span class="brand-name">ReviewPilot</span>
        <span class="brand-tag">AI PR 评审</span>
      </div>

      <form class="search" @submit.prevent="emit('submit')">
        <label class="visually-hidden" for="pr-url">GitHub PR 地址</label>
        <div class="search-field">
          <input
            id="pr-url"
            ref="inputRef"
            v-model="url"
            class="url-input"
            type="text"
            inputmode="url"
            placeholder="粘贴 GitHub PR 地址，如 github.com/owner/repo/pull/12"
            autocomplete="off"
            spellcheck="false"
            :disabled="props.loading"
            :aria-invalid="props.fieldError ? 'true' : undefined"
            aria-describedby="pr-url-hint"
          />
          <kbd class="slash-hint" aria-hidden="true">/</kbd>
        </div>
        <button type="submit" class="btn-go" :disabled="props.loading">
          <span v-if="props.loading" class="spinner" aria-hidden="true"></span>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polygon points="5 3 19 12 5 21 5 3"/>
          </svg>
          <span>{{ props.loading ? '分析中…' : '开始分析' }}</span>
        </button>
      </form>
      <p v-if="props.fieldError" id="pr-url-hint" class="field-error" role="alert">
        {{ props.fieldError }}
      </p>
      <p v-else id="pr-url-hint" class="visually-hidden">格式：https://github.com/所有者/仓库/pull/编号</p>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: var(--space-3);
  z-index: 100;
  border: none;
  border-radius: var(--radius-lg);
  max-width: 884px;
  margin: 0 auto;
  width: calc(100% - var(--space-6));
}
.topbar-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  background: linear-gradient(120deg, var(--violet), #0369A1 60%, var(--green));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.brand svg { color: var(--violet); flex-shrink: 0; }
.brand-name {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  color: var(--fg);
  letter-spacing: -0.01em;
}
.brand-tag {
  font-size: 12px;
  color: var(--fg-subtle);
  border-left: 1px solid var(--border);
  padding-left: var(--space-2);
}

.search { display: flex; gap: var(--space-2); }
.search-field {
  position: relative;
  flex: 1;
  min-width: 0;
}
.url-input {
  width: 100%;
  height: 44px;
  padding: 0 44px 0 var(--space-3);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius);
  color: var(--fg);
  font-family: var(--font-mono);
  font-size: 14px;
  transition: border-color 150ms, box-shadow 150ms;
}
.url-input::placeholder { color: var(--fg-subtle); }
.url-input:focus { border-color: var(--violet); outline: none; box-shadow: 0 0 0 3px var(--violet-soft); }
.url-input:focus-visible { outline: none; }
.url-input:disabled { opacity: 0.6; cursor: wait; }
.url-input[aria-invalid='true'] { border-color: var(--red); }

.slash-hint {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-subtle);
  border: 1px solid var(--border);
  border-bottom-width: 2px;
  border-radius: var(--radius-sm);
  padding: 1px 6px;
  pointer-events: none;
}
@media (max-width: 640px) { .slash-hint { display: none; } }

.btn-go {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 44px;
  min-width: 44px;
  padding: 0 var(--space-4);
  background: var(--brand-gradient);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--radius);
  color: var(--on-brand);
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: filter 150ms, transform 100ms, box-shadow 150ms;
  box-shadow: 0 4px 18px rgba(79, 70, 229, 0.35);
  white-space: nowrap;
}
.btn-go:hover:not(:disabled) { filter: brightness(1.08); box-shadow: 0 6px 24px rgba(79, 70, 229, 0.45); }
.btn-go:active:not(:disabled) { transform: scale(0.98); }
.btn-go:disabled { opacity: 0.55; cursor: not-allowed; box-shadow: none; }

.spinner {
  width: 14px; height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.85);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.field-error {
  font-size: 13px;
  color: var(--red);
}
</style>
