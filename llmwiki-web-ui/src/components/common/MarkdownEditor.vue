<script setup lang="ts">
import { computed } from 'vue'
// @ts-expect-error bytemd vue-next lacks full TS typings
import { Editor } from '@bytemd/vue-next'
import gfm from '@bytemd/plugin-gfm'
import highlight from '@bytemd/plugin-highlight'
import zhHans from 'bytemd/locales/zh_Hans.json'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  minHeight?: string
  mode?: 'split' | 'tab' | 'auto'
}>(), {
  placeholder: '',
  minHeight: '420px',
  mode: 'split',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const plugins = [gfm(), highlight()]

function onChange(v: string) {
  emit('update:modelValue', v)
}

const wrapperStyle = computed(() => ({
  '--md-editor-min-height': props.minHeight,
}))
</script>

<template>
  <div class="md-editor-wrapper" :style="wrapperStyle">
    <Editor
      :value="props.modelValue"
      :plugins="plugins"
      :mode="props.mode"
      :placeholder="props.placeholder"
      :locale="zhHans"
      @change="onChange"
    />
  </div>
</template>

<style scoped>
.md-editor-wrapper {
  width: 100%;
}

.md-editor-wrapper :deep(.bytemd) {
  height: var(--md-editor-min-height);
  min-height: var(--md-editor-min-height);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-card);
}
</style>
