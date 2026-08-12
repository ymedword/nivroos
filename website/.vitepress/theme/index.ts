import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import Layout from './Layout.vue'
import Home from './components/Home.vue'
import ArchitectureDiagram from './components/ArchitectureDiagram.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout,
  enhanceApp({ app }) {
    app.component('Home', Home)
    app.component('ArchitectureDiagram', ArchitectureDiagram)
  },
} satisfies Theme
