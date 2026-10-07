<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const STATUSES = ['窖藏中', '可售', '已售罄']
const STATUS_TAG = { '窖藏中': 'warning', '可售': 'success', '已售罄': 'info' }
const FILTERS = ['全部', ...STATUSES]

const batches = ref([])
const wines = ref([])
const wineMap = computed(() => Object.fromEntries(wines.value.map(w => [w.id, w])))
const loading = ref(false)
const filter = ref('全部')

const shown = computed(() =>
  filter.value === '全部' ? batches.value : batches.value.filter(b => b.status === filter.value)
)

async function load() {
  loading.value = true
  try {
    const [b, w] = await Promise.all([api.listCellarBatches(), api.listWines()])
    batches.value = b; wines.value = w
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 新增（一律窖藏中，放行后才可售） ----------------
const dialog = ref(false)
const form = reactive({ wineId: null, batchNo: '', bottleCount: 1, cellarDate: '' })

function openCreate() {
  Object.assign(form, { wineId: null, batchNo: '', bottleCount: 1, cellarDate: '' })
  dialog.value = true
}
async function submit() {
  try {
    await api.createCellarBatch({ ...form, status: '窖藏中' })
    ElMessage.success('已新建窖藏批次（窖藏中）')
    dialog.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

const releasing = ref(new Set())
async function release(b) {
  if (releasing.value.has(b.id)) return
  releasing.value.add(b.id)
  try {
    await api.releaseCellarBatch(b.id)
    ElMessage.success(`批次 ${b.batchNo} 已放行可售，瓶数已计入酒款库存`)
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
  finally { releasing.value.delete(b.id) }
}

function tagType(b) {
  return STATUS_TAG[b.status]
}

// 批次所属酒款是否停售（停售后批次可售展示同步关闭，与酒款台账/销售出窖页同一套资格结果）
function wineDelisted(b) {
  return wineMap.value[b.wineId]?.status === '停售'
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <el-radio-group v-model="filter">
        <el-radio-button v-for="f in FILTERS" :key="f" :value="f">{{ f }}</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openCreate">新增批次</el-button>
    </div>

    <div class="tip">
      可用瓶数 = 总瓶数 − 已出窖瓶数；仅「可售」批次的可用瓶数会计入酒款库存，出窖按入窖日最早批次优先扣减。
    </div>

    <div class="grid">
      <el-card v-for="b in shown" :key="b.id" shadow="hover" class="card">
        <div class="card-head">
          <span class="batch-no">{{ b.batchNo }}</span>
          <span class="head-tags">
            <el-tag v-if="wineDelisted(b)" size="small" type="info" effect="dark">酒款停售</el-tag>
            <el-tag size="small" :type="tagType(b)">{{ b.status }}</el-tag>
          </span>
        </div>
        <div class="card-body">
          <div class="row"><span class="k">酒款</span><span class="v">{{ wineMap[b.wineId]?.name || '—' }}</span></div>
          <div class="row">
            <span class="k">入窖日</span><span class="v">{{ b.cellarDate }}</span>
          </div>
          <div class="bottles">
            <div class="b-main">
              <span class="b-available" :class="{ zero: b.availableBottles === 0 }">{{ b.availableBottles }}</span>
              <span class="b-unit">瓶可用</span>
            </div>
            <div class="b-sub">总 {{ b.bottleCount }} 瓶 · 已出窖 {{ b.soldCount }} 瓶</div>
            <el-progress
              :percentage="b.bottleCount ? Math.round(b.soldCount / b.bottleCount * 100) : 0"
              :stroke-width="6" :show-text="false"
              :status="b.availableBottles === 0 ? 'success' : ''"
            />
          </div>
        </div>
        <div class="card-foot">
          <el-button
            v-if="b.status === '窖藏中'"
            type="success" size="small"
            :loading="releasing.has(b.id)"
            @click="release(b)"
          >放行可售</el-button>
          <span v-else-if="b.status === '可售' && wineDelisted(b)" class="muted delisted">酒款停售中 · 暂不进入销售选择（复售后恢复）</span>
          <span v-else-if="b.status === '可售'" class="muted">已放行 · 出窖时按 FIFO 扣减</span>
          <span v-else class="muted">已售罄 · 退窖可回补恢复</span>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && shown.length === 0" description="暂无批次" />

    <el-dialog v-model="dialog" title="新增窖藏批次" width="440px">
      <el-form label-width="80px">
        <el-form-item label="关联酒款">
          <el-select v-model="form.wineId" filterable placeholder="选择酒款">
            <el-option v-for="w in wines" :key="w.id" :label="`${w.code} ${w.name}`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次号"><el-input v-model="form.batchNo" placeholder="如 B-2025-09" /></el-form-item>
        <el-form-item label="瓶数"><el-input-number v-model="form.bottleCount" :min="1" /></el-form-item>
        <el-form-item label="入窖日"><el-date-picker v-model="form.cellarDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <div class="form-note">新批次统一为「窖藏中」，经放行后进入可售，不支持直接建可售批次。</div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.tip { font-size: 12px; color: var(--muted); margin-bottom: 14px; line-height: 1.6; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.card { border-radius: 12px; }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.head-tags { display: flex; gap: 6px; }
.batch-no { font-weight: 700; color: var(--primary-deep); }
.card-body { font-size: 13px; }
.row { display: flex; justify-content: space-between; padding: 3px 0; }
.k { color: var(--muted); }
.v { color: var(--ink); }
.bottles { margin-top: 8px; padding: 8px 10px; background: #faf4f6; border-radius: 8px; }
.b-main { display: flex; align-items: baseline; gap: 4px; }
.b-available { font-size: 22px; font-weight: 800; color: var(--primary); line-height: 1.1; }
.b-available.zero { color: var(--muted); }
.b-unit { font-size: 12px; color: var(--muted); }
.b-sub { font-size: 12px; color: var(--muted); margin: 2px 0 6px; }
.card-foot { margin-top: 12px; }
.muted { color: var(--muted); font-size: 12px; }
.delisted { color: #b26a00; }
.form-note { font-size: 12px; color: var(--muted); padding-left: 80px; line-height: 1.5; }
</style>
