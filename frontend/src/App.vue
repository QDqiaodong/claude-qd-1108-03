<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import WineView from './views/WineView.vue'
import CellarBatchView from './views/CellarBatchView.vue'
import MemberView from './views/MemberView.vue'
import SaleOutView from './views/SaleOutView.vue'

// ------------------------------------------------------------------
// 导航形态：左侧竖排导航（el-menu vertical）。
// 顶部品牌「葡萄酒窖」，左侧竖排四个业务模块，点击切换路由。
// ------------------------------------------------------------------

const modules = [
  { path: '/wines',         label: '酒款台账',   sub: '产区 · 类型', icon: '🍇', comp: WineView },
  { path: '/cellar-batches', label: '窖藏批次',   sub: '窖藏 · 放行', icon: '🛢️', comp: CellarBatchView },
  { path: '/members',       label: '会员',       sub: '等级 · 积分', icon: '👤', comp: MemberView },
  { path: '/sale-outs',     label: '销售出窖',   sub: '出窖 · 退窖', icon: '🧾', comp: SaleOutView }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({})

const activeIndex = computed(() => currentPath.value)

const currentView = computed(() => {
  const m = modules.find((mm) => mm.path === currentPath.value)
  return m ? m.comp : WineView
})

async function loadCounts() {
  try {
    const [w, b, m, s] = await Promise.all([
      api.listWines(), api.listCellarBatches(),
      api.listMembers(), api.listSaleOuts()
    ])
    counts.value = {
      '/wines': w.length,
      '/cellar-batches': b.length,
      '/members': m.length,
      '/sale-outs': s.length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function go(path) {
  router.push(path)
}

function handleSelect(index) {
  go(index)
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })

onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <!-- 左侧竖排导航 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">酒</div>
        <div class="brand-text">
          <div class="brand-title">葡萄酒窖</div>
          <div class="brand-sub">酒款台账与窖藏批次</div>
        </div>
      </div>
      <el-menu
        class="side-menu"
        :default-active="activeIndex"
        mode="vertical"
        @select="handleSelect"
      >
        <el-menu-item v-for="m in modules" :key="m.path" :index="m.path">
          <span class="m-icon">{{ m.icon }}</span>
          <span class="m-label">{{ m.label }}</span>
          <span class="m-count" v-if="counts[m.path] !== undefined">{{ counts[m.path] }}</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- 右侧内容区 -->
    <main class="content">
      <div class="content-head">
        <span class="crumb">{{ modules.find(m => m.path === currentPath)?.label || '酒款台账' }}</span>
        <span class="crumb-sub">{{ modules.find(m => m.path === currentPath)?.sub }}</span>
      </div>
      <section class="panel">
        <component :is="currentView" @changed="loadCounts" />
      </section>
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; display: flex; }

.sidebar {
  width: 220px; flex-shrink: 0;
  background: linear-gradient(180deg, var(--primary-deep) 0%, var(--primary) 100%);
  color: #fff; display: flex; flex-direction: column;
  box-shadow: 2px 0 12px rgba(123, 45, 74, 0.25);
}
.brand { display: flex; align-items: center; gap: 10px; padding: 18px 16px; }
.brand-mark {
  width: 40px; height: 40px; border-radius: 10px;
  background: #fff; color: var(--primary-deep);
  display: flex; align-items: center; justify-content: center;
  font-weight: 800; font-size: 20px;
}
.brand-title { font-size: 17px; font-weight: 700; }
.brand-sub { font-size: 11px; opacity: 0.8; margin-top: 2px; }

.side-menu {
  background: transparent !important;
  border-right: none;
  flex: 1;
}
.side-menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.82);
  height: 54px; margin: 4px 10px; border-radius: 10px;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: rgba(255, 255, 255, 0.18) !important;
  color: #fff !important; font-weight: 700;
}
.side-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.12) !important;
}
.m-icon { font-size: 18px; margin-right: 8px; }
.m-label { flex: 1; }
.m-count {
  min-width: 22px; height: 22px; padding: 0 6px; border-radius: 11px;
  background: rgba(255, 255, 255, 0.25); color: #fff; font-size: 12px;
  display: flex; align-items: center; justify-content: center;
}

.content { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.content-head { padding: 18px 24px 0; }
.crumb { font-size: 18px; font-weight: 700; color: var(--primary-deep); }
.crumb-sub { font-size: 12px; color: var(--muted); margin-left: 10px; }
.panel { flex: 1; padding: 16px 24px 24px; overflow: auto; }
</style>
