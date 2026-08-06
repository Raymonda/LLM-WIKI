<script setup lang="ts">
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import ToastContainer from '@/components/common/ToastContainer.vue'

const { locale } = useI18n({ useScope: 'global' })
const elLocale = computed(() => (locale.value === 'en' ? en : zhCn))
</script>

<template>
  <ElConfigProvider :locale="elLocale">
    <RouterView />
    <ToastContainer />
  </ElConfigProvider>
</template>