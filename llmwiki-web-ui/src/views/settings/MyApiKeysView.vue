<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import McpAgentPanel from '@/components/system/McpAgentPanel.vue'

const { t } = useI18n()
</script>

<template>
  <div class="my-api-keys">
    <h1 class="my-api-keys__title">{{ t('nav.myApiKeys') }}</h1>
    <McpAgentPanel />
  </div>
</template>

<style scoped>
.my-api-keys {
  max-width: 820px;
}

.my-api-keys__title {
  margin: 0 0 var(--space-6) 0;
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}
</style>
