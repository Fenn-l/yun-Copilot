<template>
  <div class="markdown-body" v-html="html"></div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  content: { type: String, default: '' },
})

const html = computed(() => {
  const raw = marked.parse(props.content || '', { breaks: true, gfm: true })
  return DOMPurify.sanitize(raw)
})
</script>
