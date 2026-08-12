<script setup lang="ts">
import { withBase } from 'vitepress'

defineProps<{ isZh: boolean }>()
</script>

<template>
  <section class="quickstart-section shell">
    <div class="quickstart-panel">
      <div class="quickstart-copy"><span class="section-index">{{ isZh ? '03 / 快速开始' : '03 / QUICK START' }}</span><h2 v-if="isZh">从一个目录开始。</h2><h2 v-else>Start with a directory.</h2><p v-if="isZh">一个 Agent 目录，一份 AGENT.md，再接入你已有的 MCP 能力。NivroOS 让业务 Agent 的定义保持简单。</p><p v-else>One agent directory, one AGENT.md, and the MCP capabilities you already use. NivroOS keeps the definition of a business agent simple.</p><a class="text-link" :href="withBase(isZh ? '/zh/quick-start' : '/quick-start')">{{ isZh ? '阅读快速开始' : 'Read the quick start' }} <span>↗</span></a></div>
      <div class="terminal-card"><div class="terminal-top"><span>TERMINAL</span><span>nivroos</span></div><pre><code><span class="muted">$</span> nivroos init
<span class="muted">$</span> nivroos profile create ops-agent
<span class="muted">$</span> nivroos chat --profile ops-agent

<span class="prompt">agent</span>  Ready for your task.</code></pre></div>
    </div>
  </section>
</template>
