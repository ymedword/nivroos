<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ isZh: boolean }>()

const copy = computed(() => props.isZh ? {
  title: 'NivroOS — 整体架构', subtitle: 'Java 21 · Spring Boot 3.x · 单体 JAR',
  providers: '大模型 Provider', p1: 'DeepSeek · Qwen · Kimi', p2: 'OpenAI · Anthropic', p3: 'Ollama（本地）', pFoot: 'Spring AI Alibaba',
  mcp: 'MCP 服务', m1: 'github-mcp · news-mcp', m2: '自定义 MCP Server', m3: 'AGENT.md 目录（零代码）', mFoot: 'MCP JSON-RPC 协议',
  notify: '通知目标', notifyOne: '企业微信 · 飞书 · 钉钉', notifyFoot: 'Generic Webhook', users: '用户', usersFoot: 'nivroos chat（CLI）', business: '业务系统', businessFoot: 'HTTP POST /api/v1/…',
  channel: '接入 / 触发层', cli: 'CLI · 人工触发', rest: 'REST API · 人工触发', scheduler: 'Scheduler · 定时触发', agent: 'Agent 层 · 一个目录 = 一个 Agent', body: '正文 · 做什么（AGENT.md）', profile: 'frontmatter · 谁 / 何时 / 怎么跑',
  engine: '引擎层 · nivroos-core', react: 'ReActLoop', reactOne: '推理 + 行动', reactTwo: '最多 10 轮', prompt: 'PromptBuilder', promptOne: '系统 + 记忆', promptTwo: '+ 历史 + 工具', executor: 'ToolExecutor', executorOne: 'Sandbox 校验', executorTwo: '+ 审计写入',
  capability: '能力层', provider: 'Provider', providerFoot: 'LLM 路由', memory: 'Memory', memoryFoot: '会话 + 长期', tool: 'Tool', toolFoot: '内置 + MCP', notifyLayer: 'Notify', notifyLayerFoot: '出站推送', storage: '存储层 · nivroos-storage', sqlite: 'SQLite（nivroos.db）', sqliteFoot: 'sessions · tool_invocations · llm_calls', filesystem: 'Filesystem（.nivroos/）', filesystemFoot: 'profiles · MEMORY.md · skills',
  legend: '图例', legendText: '蓝 = 触发入口　粉 = Agent 定义　橙 = 引擎　紫 = 能力　绿 = 存储　灰 = 外部依赖', legendFoot: '实线 = 内部调用　　虚线 = 外部调用',
} : {
  title: 'NivroOS — Runtime Architecture', subtitle: 'Java 21 · Spring Boot 3.x · Single JAR',
  providers: 'LLM PROVIDERS', p1: 'DeepSeek · Qwen · Kimi', p2: 'OpenAI · Anthropic', p3: 'Ollama (local)', pFoot: 'Spring AI Alibaba',
  mcp: 'MCP SERVERS', m1: 'github-mcp · news-mcp', m2: 'Custom MCP servers', m3: 'AGENT.md directory (zero code)', mFoot: 'MCP JSON-RPC protocol',
  notify: 'NOTIFY TARGETS', notifyOne: 'WeCom · Feishu · DingTalk', notifyFoot: 'Generic Webhook', users: 'USERS', usersFoot: 'nivroos chat (CLI)', business: 'BUSINESS SYSTEMS', businessFoot: 'HTTP POST /api/v1/…',
  channel: 'Channel / Trigger Layer', cli: 'CLI · human', rest: 'REST API · human', scheduler: 'Scheduler · clock', agent: 'Agent Layer · one directory = one Agent', body: 'Body · what to do (AGENT.md)', profile: 'Frontmatter · who / when / how',
  engine: 'Engine Layer · nivroos-core', react: 'ReActLoop', reactOne: 'Reason + Act', reactTwo: 'max 10 iterations', prompt: 'PromptBuilder', promptOne: 'system + memory', promptTwo: '+ history + tools', executor: 'ToolExecutor', executorOne: 'Sandbox check', executorTwo: '+ audit write',
  capability: 'Capability Layer', provider: 'Provider', providerFoot: 'LLM routing', memory: 'Memory', memoryFoot: 'session + long-term', tool: 'Tool', toolFoot: 'built-in + MCP', notifyLayer: 'Notify', notifyLayerFoot: 'outbound push', storage: 'Storage Layer · nivroos-storage', sqlite: 'SQLite (nivroos.db)', sqliteFoot: 'sessions · tool_invocations · llm_calls', filesystem: 'Filesystem (.nivroos/)', filesystemFoot: 'profiles · MEMORY.md · skills',
  legend: 'LEGEND', legendText: 'Blue = triggers　Pink = agent definition　Amber = engine　Purple = capabilities　Green = storage　Gray = external', legendFoot: 'Solid = internal call　　Dashed = external call',
})
</script>

<template>
  <div class="architecture-diagram" role="img" :aria-label="copy.title">
    <svg viewBox="0 0 1200 820" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">
      <defs>
        <marker id="arch-gray" markerWidth="10" markerHeight="10" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#737373" /></marker>
        <marker id="arch-blue" markerWidth="10" markerHeight="10" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#60a5fa" /></marker>
        <marker id="arch-purple" markerWidth="10" markerHeight="10" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#c084fc" /></marker>
        <marker id="arch-pink" markerWidth="10" markerHeight="10" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#f472b6" /></marker>
      </defs>
      <rect width="1200" height="820" fill="#050505" />
      <text x="600" y="38" fill="#ffffff" font-size="22" font-weight="700" text-anchor="middle">{{ copy.title }}</text>
      <text x="600" y="61" fill="#737373" font-size="11" text-anchor="middle">{{ copy.subtitle }}</text>

      <g>
        <rect x="24" y="82" width="220" height="150" rx="12" fill="#0c0c0c" stroke="#525252" stroke-dasharray="6 4" /><text x="134" y="106" fill="#d4d4d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.providers }}</text>
        <rect x="40" y="120" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="134" y="138" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.p1 }}</text>
        <rect x="40" y="153" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="134" y="171" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.p2 }}</text>
        <rect x="40" y="186" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="134" y="204" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.p3 }}</text><text x="134" y="224" fill="#737373" font-size="9" text-anchor="middle">{{ copy.pFoot }}</text>
        <rect x="956" y="82" width="220" height="150" rx="12" fill="#0c0c0c" stroke="#525252" stroke-dasharray="6 4" /><text x="1066" y="106" fill="#d4d4d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.mcp }}</text>
        <rect x="972" y="120" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="1066" y="138" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.m1 }}</text>
        <rect x="972" y="153" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="1066" y="171" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.m2 }}</text>
        <rect x="972" y="186" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="1066" y="204" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.m3 }}</text><text x="1066" y="224" fill="#737373" font-size="9" text-anchor="middle">{{ copy.mFoot }}</text>
        <rect x="956" y="250" width="220" height="108" rx="12" fill="#0c0c0c" stroke="#525252" stroke-dasharray="6 4" /><text x="1066" y="275" fill="#d4d4d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.notify }}</text><rect x="972" y="290" width="188" height="27" rx="6" fill="#171717" stroke="#404040" /><text x="1066" y="308" fill="#d4d4d4" font-size="10" text-anchor="middle">{{ copy.notifyOne }}</text><text x="1066" y="342" fill="#737373" font-size="9" text-anchor="middle">{{ copy.notifyFoot }}</text>
        <rect x="24" y="690" width="220" height="64" rx="12" fill="#0c0c0c" stroke="#525252" stroke-dasharray="6 4" /><text x="134" y="716" fill="#d4d4d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.users }}</text><text x="134" y="738" fill="#a3a3a3" font-size="10" text-anchor="middle">{{ copy.usersFoot }}</text>
        <rect x="956" y="690" width="220" height="64" rx="12" fill="#0c0c0c" stroke="#525252" stroke-dasharray="6 4" /><text x="1066" y="716" fill="#d4d4d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.business }}</text><text x="1066" y="738" fill="#a3a3a3" font-size="10" text-anchor="middle">{{ copy.businessFoot }}</text>
      </g>

      <g>
        <rect x="300" y="82" width="600" height="610" rx="16" fill="#090909" stroke="#626262" stroke-width="2" /><text x="600" y="108" fill="#ffffff" font-size="16" font-weight="700" text-anchor="middle">NivroOS</text>
        <rect x="320" y="130" width="560" height="78" rx="10" fill="#0b1b2d" stroke="#3b82f6" /><text x="340" y="152" fill="#93c5fd" font-size="12" font-weight="700">{{ copy.channel }}</text>
        <rect x="336" y="164" width="166" height="28" rx="6" fill="#12345a" stroke="#3b82f6" /><text x="419" y="182" fill="#bfdbfe" font-size="10" text-anchor="middle">{{ copy.cli }}</text><rect x="517" y="164" width="166" height="28" rx="6" fill="#12345a" stroke="#3b82f6" /><text x="600" y="182" fill="#bfdbfe" font-size="10" text-anchor="middle">{{ copy.rest }}</text><rect x="698" y="164" width="166" height="28" rx="6" fill="#12345a" stroke="#3b82f6" /><text x="781" y="182" fill="#bfdbfe" font-size="10" text-anchor="middle">{{ copy.scheduler }}</text>
        <rect x="320" y="226" width="560" height="70" rx="10" fill="#2b1022" stroke="#ec4899" /><text x="600" y="249" fill="#f9a8d4" font-size="12" font-weight="700" text-anchor="middle">{{ copy.agent }}</text><rect x="370" y="260" width="215" height="28" rx="6" fill="#4a1739" stroke="#ec4899" /><text x="477.5" y="278" fill="#fbcfe8" font-size="10" text-anchor="middle">{{ copy.body }}</text><rect x="615" y="260" width="215" height="28" rx="6" fill="#4a1739" stroke="#ec4899" /><text x="722.5" y="278" fill="#fbcfe8" font-size="10" text-anchor="middle">{{ copy.profile }}</text>
        <rect x="320" y="314" width="560" height="104" rx="10" fill="#080808" stroke="#303030" /><line x1="320" y1="344" x2="880" y2="344" stroke="#303030" /><circle cx="340" cy="329" r="4" fill="#f59e0b" /><circle cx="354" cy="329" r="4" fill="#525252" /><circle cx="368" cy="329" r="4" fill="#525252" /><text x="392" y="333" fill="#737373" font-size="10" letter-spacing="1">{{ copy.engine }}</text>
        <text x="344" y="367" fill="#fbbf24" font-size="10" font-weight="700">› {{ copy.react }}</text><text x="344" y="389" fill="#a3a3a3" font-size="8.5">{{ copy.reactOne }} · {{ copy.reactTwo }}</text>
        <text x="532" y="367" fill="#fbbf24" font-size="10" font-weight="700">› {{ copy.prompt }}</text><text x="532" y="389" fill="#a3a3a3" font-size="8.5">{{ copy.promptOne }} {{ copy.promptTwo }}</text>
        <text x="704" y="367" fill="#fbbf24" font-size="10" font-weight="700">› {{ copy.executor }}</text><text x="704" y="389" fill="#a3a3a3" font-size="8.5">{{ copy.executorOne }} {{ copy.executorTwo }}</text>
        <rect x="320" y="436" width="560" height="112" rx="10" fill="#21142d" stroke="#a855f7" /><text x="340" y="460" fill="#d8b4fe" font-size="12" font-weight="700">{{ copy.capability }}</text>
        <rect x="336" y="473" width="122" height="58" rx="6" fill="#382050" stroke="#a855f7" /><text x="397" y="495" fill="#e9d5ff" font-size="10" font-weight="700" text-anchor="middle">{{ copy.provider }}</text><text x="397" y="513" fill="#d8b4fe" font-size="8.5" text-anchor="middle">{{ copy.providerFoot }}</text><rect x="470" y="473" width="122" height="58" rx="6" fill="#382050" stroke="#a855f7" /><text x="531" y="495" fill="#e9d5ff" font-size="10" font-weight="700" text-anchor="middle">{{ copy.memory }}</text><text x="531" y="513" fill="#d8b4fe" font-size="8.5" text-anchor="middle">{{ copy.memoryFoot }}</text><rect x="604" y="473" width="122" height="58" rx="6" fill="#382050" stroke="#a855f7" /><text x="665" y="495" fill="#e9d5ff" font-size="10" font-weight="700" text-anchor="middle">{{ copy.tool }}</text><text x="665" y="513" fill="#d8b4fe" font-size="8.5" text-anchor="middle">{{ copy.toolFoot }}</text><rect x="738" y="473" width="126" height="58" rx="6" fill="#382050" stroke="#a855f7" /><text x="801" y="495" fill="#e9d5ff" font-size="10" font-weight="700" text-anchor="middle">{{ copy.notifyLayer }}</text><text x="801" y="513" fill="#d8b4fe" font-size="8.5" text-anchor="middle">{{ copy.notifyLayerFoot }}</text>
        <rect x="320" y="566" width="560" height="86" rx="10" fill="#0d2818" stroke="#22c55e" /><text x="340" y="590" fill="#86efac" font-size="12" font-weight="700">{{ copy.storage }}</text><rect x="336" y="603" width="258" height="34" rx="6" fill="#123b24" stroke="#22c55e" /><text x="465" y="617" fill="#bbf7d0" font-size="10" font-weight="700" text-anchor="middle">{{ copy.sqlite }}</text><text x="465" y="630" fill="#86efac" font-size="8" text-anchor="middle">{{ copy.sqliteFoot }}</text><rect x="606" y="603" width="258" height="34" rx="6" fill="#123b24" stroke="#22c55e" /><text x="735" y="617" fill="#bbf7d0" font-size="10" font-weight="700" text-anchor="middle">{{ copy.filesystem }}</text><text x="735" y="630" fill="#86efac" font-size="8" text-anchor="middle">{{ copy.filesystemFoot }}</text>
      </g>
      <g fill="none" stroke-width="1.5" stroke-dasharray="6 4"><line x1="244" y1="150" x2="300" y2="490" stroke="#c084fc" marker-end="url(#arch-purple)" /><line x1="956" y1="150" x2="900" y2="490" stroke="#c084fc" marker-end="url(#arch-purple)" /><line x1="956" y1="304" x2="900" y2="515" stroke="#f472b6" marker-end="url(#arch-pink)" /><line x1="134" y1="690" x2="320" y2="170" stroke="#60a5fa" marker-end="url(#arch-blue)" /><line x1="1066" y1="690" x2="880" y2="170" stroke="#60a5fa" marker-end="url(#arch-blue)" /></g>
      <g fill="none" stroke="#737373" stroke-width="1.7"><line x1="600" y1="208" x2="600" y2="226" marker-end="url(#arch-gray)" /><line x1="600" y1="296" x2="600" y2="314" marker-end="url(#arch-gray)" /><line x1="600" y1="418" x2="600" y2="436" marker-end="url(#arch-gray)" /><line x1="600" y1="548" x2="600" y2="566" marker-end="url(#arch-gray)" /></g>
      <rect x="300" y="712" width="600" height="74" rx="10" fill="#0b0b0b" stroke="#303030" /><text x="600" y="734" fill="#737373" font-size="10" text-anchor="middle" letter-spacing="1">{{ copy.legend }}</text><text x="600" y="754" fill="#a3a3a3" font-size="10" text-anchor="middle">{{ copy.legendText }}</text><text x="600" y="770" fill="#737373" font-size="10" text-anchor="middle">{{ copy.legendFoot }}</text>
    </svg>
  </div>
</template>
