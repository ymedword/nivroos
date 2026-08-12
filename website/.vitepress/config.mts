import { defineConfig } from 'vitepress'
import type { DefaultTheme } from 'vitepress/theme'

const github = 'https://github.com/ymedword/nivroos'

const navEn: DefaultTheme.NavItem[] = [
  { text: 'Guide', link: '/guide' },
  { text: 'Architecture', link: '/architecture' },
  { text: 'Quick Start', link: '/quick-start' },
  { text: 'GitHub', link: github },
]

const navZh: DefaultTheme.NavItem[] = [
  { text: '指南', link: '/zh/guide' },
  { text: '架构', link: '/zh/architecture' },
  { text: '快速开始', link: '/zh/quick-start' },
  { text: 'GitHub', link: github },
]

const sidebarEn: DefaultTheme.Sidebar = [
  {
    text: 'Introduction',
    items: [
      { text: 'What is NivroOS?', link: '/guide' },
      { text: 'Architecture', link: '/architecture' },
      { text: 'Quick Start', link: '/quick-start' },
    ],
  },
]

const sidebarZh: DefaultTheme.Sidebar = [
  {
    text: '介绍',
    items: [
      { text: 'NivroOS 是什么？', link: '/zh/guide' },
      { text: '整体架构', link: '/zh/architecture' },
      { text: '快速开始', link: '/zh/quick-start' },
    ],
  },
]

export default defineConfig({
  title: 'NivroOS',
  description: 'An open-source Agent OS for private, auditable enterprise AI.',
  base: '/nivroos/',
  cleanUrls: true,
  lastUpdated: true,
  appearance: 'force-dark',
  head: [
    ['meta', { name: 'theme-color', content: '#090909' }],
    ['meta', { name: 'author', content: 'NivroOS contributors' }],
    ['meta', { name: 'keywords', content: 'Agent OS, Java, enterprise AI, Spring Boot, private deployment, MCP' }],
    ['link', { rel: 'icon', href: '/nivroos/images/nivroos-logo.svg' }],
  ],
  locales: {
    root: {
      label: 'English',
      lang: 'en',
      link: '/',
      themeConfig: {
        nav: navEn,
        sidebar: sidebarEn,
        footer: {
          message: 'Open source under the Apache License 2.0.',
          copyright: 'Copyright © 2026 NivroOS contributors',
        },
      },
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/zh/',
      themeConfig: {
        nav: navZh,
        sidebar: sidebarZh,
        footer: {
          message: '基于 Apache License 2.0 开源。',
          copyright: 'Copyright © 2026 NivroOS contributors',
        },
      },
    },
  },
  themeConfig: {
    logo: '/images/nivroos-logo.svg',
    siteTitle: false,
    outline: 'deep',
    search: { provider: 'local' },
    socialLinks: [{ icon: 'github', link: github }],
    footer: {
      message: 'Open source under the Apache License 2.0.',
      copyright: 'Copyright © 2026 NivroOS contributors',
    },
    nav: navEn,
    sidebar: sidebarEn,
  },
  sitemap: {
    hostname: 'https://ymedword.github.io/nivroos/',
  },
})
