<script setup lang="ts">
import { computed } from 'vue'
import { withBase } from 'vitepress'

const props = defineProps<{ isZh: boolean }>()

const copy = computed(() => props.isZh ? {
  eyebrow: '面向企业的开源 Agent OS',
  titleOne: '把 Agent 运行在',
  titleTwo: '自己的基础设施上。',
  lede: 'NivroOS 是基于 Java 的企业级 Agent OS。统一模型路由、ReAct 执行、记忆、工具和 REST 接入，让业务 Agent 在企业自己的 K8s 或服务器上运行。',
  start: '快速开始', github: '查看 GitHub',
  enterprise: '企业生态', license: '开源许可', deploy: '自主部署',
  terminalTitle: 'nivroos — bash', init: 'nivroos init',
  initialized: 'Workspace initialized at .nivroos/',
  files: 'agents/ · memory/ · sessions/ · logs/ · nivroos.db',
  chat: 'nivroos chat --profile ops-agent',
  profile: 'Loaded profile: ops-agent (deepseek-chat)',
  memory: 'Memory: 3 entries loaded from MEMORY.md',
  question: '检查一下 nginx 最近的错误日志',
  thinking: '[ops-agent] 思考中…', tool: '→ Tool: shell',
  input: '→ Input: tail -n 100 /var/log/nginx/error.log | grep "$(date +%H)"',
  sandbox: '→ SandboxChecker: ✓ allowed',
  result: '过去 1 小时发现 3 个 502 错误，均来自 upstream backend:8080',
  answer: '建议检查后端服务健康状态。需要我运行诊断命令吗？',
  caption: '你的数据，你的模型，你的 Agent。',
} : {
  eyebrow: 'OPEN-SOURCE AGENT OS FOR ENTERPRISE',
  titleOne: 'Run enterprise agents',
  titleTwo: 'where your data lives.',
  lede: 'NivroOS is a Java-based Agent OS for enterprise teams. Bring model routing, ReAct execution, memory, tools, and REST access together in one private runtime.',
  start: 'Get started', github: 'View on GitHub',
  enterprise: 'enterprise stack', license: 'open source', deploy: 'by design',
  terminalTitle: 'nivroos — bash', init: 'nivroos init',
  initialized: 'Workspace initialized at .nivroos/',
  files: 'agents/ · memory/ · sessions/ · logs/ · nivroos.db',
  chat: 'nivroos chat --profile ops-agent',
  profile: 'Loaded profile: ops-agent (deepseek-chat)',
  memory: 'Memory: 3 entries loaded from MEMORY.md',
  question: 'Check the latest Nginx error log',
  thinking: '[ops-agent] Thinking…', tool: '→ Tool: shell',
  input: '→ Input: tail -n 100 /var/log/nginx/error.log | grep "$(date +%H)"',
  sandbox: '→ SandboxChecker: ✓ allowed',
  result: 'Found 3 errors in the past hour, all from upstream backend:8080',
  answer: 'Check the backend health status. Shall I run a diagnostic command?',
  caption: 'Your data. Your models. Your agents.',
})
</script>

<template>
  <section class="home-hero shell">
    <div class="hero-copy">
      <div class="eyebrow"><span class="eyebrow-mark" /> {{ copy.eyebrow }}</div>
      <h1><span>{{ copy.titleOne }}</span><br />{{ copy.titleTwo }}</h1>
      <p class="hero-lede">{{ copy.lede }}</p>
      <div class="hero-actions">
        <a class="button button-primary" :href="withBase(props.isZh ? '/zh/quick-start' : '/quick-start')">{{ copy.start }} <span>↗</span></a>
        <a class="button button-secondary" href="https://github.com/ymedword/nivroos" target="_blank" rel="noreferrer">{{ copy.github }} <span>↗</span></a>
      </div>
      <div class="hero-meta">
        <span><strong>Java 21</strong> {{ copy.enterprise }}</span>
        <span><strong>Apache 2.0</strong> {{ copy.license }}</span>
        <span><strong>Private</strong> {{ copy.deploy }}</span>
      </div>
    </div>

    <div class="hero-visual" aria-label="NivroOS runtime terminal">
      <div class="visual-window terminal-window">
        <div class="window-bar">
          <span class="window-dots"><i class="dot-red" /><i class="dot-yellow" /><i class="dot-green" /></span>
          <span class="terminal-title">{{ copy.terminalTitle }}</span>
          <span class="terminal-index">01</span>
        </div>
        <div class="terminal-body">
          <p class="terminal-command"><span>›</span> {{ copy.init }}</p>
          <p class="terminal-success">✓ {{ copy.initialized }}</p>
          <p class="terminal-muted">{{ copy.files }}</p>
          <p class="terminal-command terminal-command-spaced"><span>›</span> {{ copy.chat }}</p>
          <p class="terminal-muted">{{ copy.profile }}</p>
          <p class="terminal-muted">{{ copy.memory }}</p>
          <p class="terminal-user"><span>you</span> {{ copy.question }}</p>
          <p class="terminal-thinking">{{ copy.thinking }}</p>
          <p class="terminal-muted">{{ copy.tool }}</p>
          <p class="terminal-muted terminal-input">{{ copy.input }}</p>
          <p class="terminal-muted">{{ copy.sandbox }}</p>
          <p class="terminal-agent">[ops-agent]</p>
          <p class="terminal-answer">{{ copy.result }}</p>
          <p class="terminal-answer">{{ copy.answer }}</p>
          <p class="terminal-command terminal-cursor"><span>›</span></p>
        </div>
      </div>
      <div class="hero-caption"><img :src="withBase('/images/nivroos-logo.svg')" alt="NivroOS" /><span>{{ copy.caption }}</span></div>
    </div>
  </section>
</template>
