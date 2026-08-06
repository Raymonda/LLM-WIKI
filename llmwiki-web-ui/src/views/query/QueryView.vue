<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div><h1>{{ t('search.queryTitle') }}</h1><p>{{ t('search.queryHint') }}</p></div>
</template>
