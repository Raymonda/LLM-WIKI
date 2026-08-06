<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = withDefaults(defineProps<{
  content: string
  inline?: boolean
}>(), {
  inline: false,
})

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
})

md.disable(['image', 'heading', 'code', 'fence', 'blockquote'])

const rendered = computed(() => {
  if (!props.content) return ''
  if (props.inline) return md.renderInline(props.content)
  return md.render(props.content)
})
</script>

<template>
  <div v-if="!inline" class="inline-markdown" v-html="rendered"></div>
  <span v-else class="inline-markdown inline-markdown--inline" v-html="rendered"></span>
</template>

<style>
.inline-markdown {
  line-height: 1.6;
}
.inline-markdown p {
  margin: 0 0 var(--space-1) 0;
}
.inline-markdown p:last-child {
  margin-bottom: 0;
}
.inline-markdown ul,
.inline-markdown ol {
  margin: var(--space-1) 0;
  padding-left: var(--space-4);
}
.inline-markdown li {
  margin-bottom: var(--space-1);
}
.inline-markdown strong {
  font-weight: 600;
}
.inline-markdown em {
  font-style: italic;
}
.inline-markdown a {
  color: var(--accent-primary);
  text-decoration: underline;
}
.inline-markdown a:hover {
  opacity: 0.85;
}
.inline-markdown code {
  background: var(--bg-secondary);
  padding: 1px 4px;
  border-radius: var(--radius-sm);
  font-size: 0.9em;
}
.inline-markdown--inline {
  line-height: inherit;
}
.inline-markdown--inline p {
  display: inline;
  margin: 0;
}
</style>