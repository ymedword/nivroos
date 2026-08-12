<script setup lang="ts">
import { nextTick, onMounted, watch } from 'vue'
import DefaultTheme from 'vitepress/theme'
import { useData, withBase } from 'vitepress'

const { lang } = useData()

const syncLogoHome = () => {
  if (typeof document === 'undefined') return

  const logoLink = document.querySelector<HTMLAnchorElement>('.VPNavBarTitle > a.title')
  if (!logoLink) return

  logoLink.href = withBase(lang.value.startsWith('zh') ? '/zh/' : '/')
}

onMounted(syncLogoHome)
watch(lang, () => nextTick(syncLogoHome))
</script>

<template>
  <DefaultTheme.Layout />
</template>
