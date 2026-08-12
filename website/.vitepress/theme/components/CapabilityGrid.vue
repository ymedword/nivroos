<script setup lang="ts">
defineProps<{ isZh: boolean }>()

const capabilities = [
  { number: '01', enTitle: 'Provider abstraction', zhTitle: '统一模型接入', en: 'Switch between leading LLM providers without changing the agent layer.', zh: '统一接入主流大模型，运行时切换 Provider，不被单一云生态锁定。', icon: '↔' },
  { number: '02', enTitle: 'Self-built ReAct loop', zhTitle: '自主 ReAct 循环', en: 'Reason, act, observe, and continue until the task is complete.', zh: '自主控制思考、工具调用和结果观察，完成多步骤任务。', icon: '◎' },
  { number: '03', enTitle: 'Layered memory', zhTitle: '分层记忆系统', en: 'Keep session context and long-term MEMORY.md close to the agent.', zh: '会话记忆与长期 MEMORY.md 协同工作，跨对话保留上下文。', icon: '▤' },
  { number: '04', enTitle: 'Tools without lock-in', zhTitle: '可扩展工具体系', en: 'Use built-in tools, MCP servers, or Java @Tool beans at your pace.', zh: '内置工具、MCP Server 和 Java @Tool Bean，按业务需要自由扩展。', icon: '⌁' },
  { number: '05', enTitle: 'Private by default', zhTitle: '默认私有部署', en: 'Run on your Kubernetes cluster or servers. Keep enterprise data in place.', zh: '部署在企业自己的 K8s 或服务器上，数据留在自己的基础设施内。', icon: '□' },
  { number: '06', enTitle: 'REST integration', zhTitle: 'REST 服务接入', en: 'Expose agents and runtime capabilities to existing business systems.', zh: '通过 REST API 向已有业务系统开放 Agent 能力。', icon: '↗' },
]
</script>

<template>
  <section class="capabilities-section shell">
    <div class="section-heading split-heading">
      <div><span class="section-index">{{ isZh ? '01 / 核心能力' : '01 / CAPABILITIES' }}</span><h2 v-if="isZh">一套底座，承载每一个 Agent。</h2><h2 v-else>One foundation.<br />Every agent.</h2></div>
      <p v-if="isZh">NivroOS 把 Agent 运行时需要的公共能力沉淀为统一底座。业务团队只需要描述 Agent，而不是重复搭建后端。</p>
      <p v-else>NivroOS turns the shared runtime capabilities every agent needs into one consistent foundation. Teams describe agents instead of rebuilding backends.</p>
    </div>
    <div class="capability-grid">
      <article v-for="item in capabilities" :key="item.number" class="capability-card">
        <div class="card-top"><span class="card-number">{{ item.number }}</span><span class="card-icon">{{ item.icon }}</span></div>
        <h3>{{ isZh ? item.zhTitle : item.enTitle }}</h3>
        <p>{{ isZh ? item.zh : item.en }}</p>
        <div class="card-rule" />
      </article>
    </div>
  </section>
</template>
