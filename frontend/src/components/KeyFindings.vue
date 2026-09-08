<script setup>
defineProps({
  items: { type: Array, default: () => [] }
})
</script>

<template>
  <section v-if="items.length" class="keypoints glass">
    <h3 class="section-label">关键发现</h3>
    <ul>
      <li v-for="(p, i) in items" :key="i">{{ p }}</li>
    </ul>
  </section>
</template>

<style scoped>
.keypoints {
  padding: var(--space-4) var(--space-5);
  border-radius: var(--radius-lg);
}
.keypoints .section-label { margin-bottom: var(--space-2); }
ul { list-style: none; display: flex; flex-direction: column; gap: var(--space-2); }
li {
  font-size: 14px;
  color: var(--fg);
  line-height: 1.6;
  padding-left: var(--space-4);
  position: relative;
}
li::before {
  content: '';
  position: absolute;
  left: 0; top: 9px;
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--green);
}
</style>
