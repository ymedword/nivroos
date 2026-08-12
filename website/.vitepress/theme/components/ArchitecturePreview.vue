<script setup lang="ts">
defineProps<{ isZh: boolean }>()
</script>

<template>
  <section class="architecture-section shell">
    <div class="section-heading split-heading architecture-heading">
      <div><span class="section-index">{{ isZh ? '02 / 架构' : '02 / ARCHITECTURE' }}</span><h2 v-if="isZh">从接入到执行，只有一条清晰链路。</h2><h2 v-else>One clear path<br />from input to outcome.</h2></div>
      <p v-if="isZh">CLI、REST API 和定时任务最终汇入同一个 AgentService，模型、记忆和工具由底层能力统一编排。</p>
      <p v-else>CLI, REST APIs, and scheduled tasks converge on the same AgentService. Models, memory, and tools stay coordinated underneath.</p>
    </div>
    <ArchitectureDiagram :is-zh="isZh" />
    <div class="architecture-facts">
      <div><strong>01</strong><span>{{ isZh ? '所有触发源进入 AgentService' : 'Every trigger enters AgentService' }}</span></div>
      <div><strong>02</strong><span>{{ isZh ? '工具执行经过 Sandbox 校验' : 'Tool execution passes Sandbox checks' }}</span></div>
      <div><strong>03</strong><span>{{ isZh ? '调用与审计数据可持久化' : 'Calls and audits are persistable' }}</span></div>
    </div>
  </section>
</template>
