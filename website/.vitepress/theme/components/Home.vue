<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'
import HomeHero from './HomeHero.vue'
import CapabilityGrid from './CapabilityGrid.vue'
import ArchitecturePreview from './ArchitecturePreview.vue'
import QuickStartBlock from './QuickStartBlock.vue'

const { lang } = useData()
const isZh = computed(() => lang.value.startsWith('zh'))
</script>

<template>
  <main class="nivroos-home">
    <HomeHero :is-zh="isZh" />
    <div class="home-divider" />
    <CapabilityGrid :is-zh="isZh" />
    <ArchitecturePreview :is-zh="isZh" />
    <QuickStartBlock :is-zh="isZh" />
    <section class="home-closing shell">
      <div class="closing-mark">N</div>
      <div>
        <span class="section-index">{{ isZh ? 'NIVROOS / 开源项目' : 'NIVROOS / OPEN SOURCE' }}</span>
        <h2 v-if="isZh">让企业 Agent 真正属于企业。</h2>
        <h2 v-else>Make enterprise agents truly yours.</h2>
      </div>
      <a class="button button-secondary" href="https://github.com/ymedword/nivroos" target="_blank" rel="noreferrer">
        {{ isZh ? '访问 GitHub' : 'Visit GitHub' }} <span>↗</span>
      </a>
    </section>
  </main>
</template>
