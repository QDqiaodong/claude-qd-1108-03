<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const orders = ref([])
const wines = ref([])
const batches = ref([])
const members = ref([])
const itemMap = ref({})   // 出窖单 id -> 逐批次明细
const loading = ref(false)

const wineMap = computed(() => Object.fromEntries(wines.value.map(w => [w.id, w])))
const memberMap = computed(() => Object.fromEntries(members.value.map(m => [m.id, m])))
const batchMap = computed(() => Object.fromEntries(batches.value.map(b => [b.id, b])))

// 每个酒款的可售批次（按入窖日升序）与可用瓶数合计
const sellableBatchesOf = (wineId) =>
  batches.value
    .filter(b => b.wineId === wineId && b.status === '可售')
    .sort((a, b) => (a.cellarDate < b.cellarDate ? -1 : a.cellarDate > b.cellarDate ? 1 : a.id - b.id))

// 可售量：优先后端随酒款列表返回的同一套资格结果（= 可售批次可用合计），缺失时本地兜底
const availableOf = (wineId) => {
  const w = wineMap.value[wineId]
  if (w && w.sellableQty !== undefined && w.sellableQty !== null) return w.sellableQty
  return sellableBatchesOf(wineId).reduce((s, b) => s + b.availableBottles, 0)
}

// 可进入出窖选择：与酒款列表/批次页同一套资格结果（在售 + 账实一致 + 有可售批次瓶数）
const sellableWines = computed(() =>
  wines.value.filter(w =>
    w.saleEligible !== undefined && w.saleEligible !== null
      ? w.saleEligible
      : w.status === '在售' && availableOf(w.id) > 0
  )
)

async function load() {
  loading.value = true
  try {
    const [o, w, b, m] = await Promise.all([
      api.listSaleOuts(), api.listWines(), api.listCellarBatches(), api.listMembers()
    ])
    orders.value = o; wines.value = w; batches.value = b; members.value = m
    // 一次性拉取各单的逐批次明细
    const entries = await Promise.all(
      o.map(async s => [s.id, await api.listSaleOutItems(s.id)])
    )
    itemMap.value = Object.fromEntries(entries)
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 出窖收银台 ----------------
const drawer = ref(false)
const memberId = ref(null)
const items = ref([])
const submitting = ref(false)

function openCreate() {
  memberId.value = null
  items.value = [{ wineId: null, qty: 1 }]
  drawer.value = true
}
function addItem() { items.value.push({ wineId: null, qty: 1 }) }
function removeItem(i) { items.value.splice(i, 1) }

// 同一酒款在多行中选择时的已占用瓶数（给每行算剩余可选上限）
function usedElsewhere(it) {
  return items.value.reduce((s, x) => (x !== it && x.wineId === it.wineId ? s + (x.qty || 0) : s), 0)
}
function maxFor(it) {
  if (!it.wineId) return 999
  return Math.max(0, availableOf(it.wineId) - usedElsewhere(it))
}
function onWineChange(it) { it.qty = 1 }

// 模拟 FIFO：某行数量将依次落在哪些批次（前台仅预览，最终以后端扣减为准）
const previewMap = computed(() => {
  const m = {}
  // 若同一酒款有多行，按行顺序累计模拟
  const consumed = {}
  for (const [i, it] of items.value.entries()) {
    if (!it.wineId || !it.qty) { m[i] = []; continue }
    let left = it.qty
    const rows = []
    for (const b of sellableBatchesOf(it.wineId)) {
      const used = consumed[b.id] || 0
      const can = b.availableBottles - used
      if (can <= 0 || left <= 0) continue
      const take = Math.min(can, left)
      rows.push({ batchNo: b.batchNo, qty: take })
      consumed[b.id] = used + take
      left -= take
    }
    if (left > 0) rows.push({ batchNo: '可售量不足', qty: left, short: true })
    m[i] = rows
  }
  return m
})

const total = computed(() =>
  items.value.reduce((sum, it) => {
    const w = wineMap.value[it.wineId]
    return sum + (w ? Number(w.price) * (it.qty || 0) : 0)
  }, 0)
)

async function submit() {
  if (submitting.value) return          // 防重复提交
  const payload = {
    memberId: memberId.value || null,
    items: items.value.filter(it => it.wineId).map(it => ({ wineId: it.wineId, qty: it.qty }))
  }
  if (payload.items.length === 0) { ElMessage.warning('请至少选择一款酒'); return }
  // 提交前再按批次可用量拦一次（后端仍会行锁复核）
  for (const it of payload.items) {
    if (it.qty > availableOf(it.wineId)) {
      const w = wineMap.value[it.wineId]
      ElMessage.error(`酒款「${w?.name}」可售批次仅剩 ${availableOf(it.wineId)} 瓶，无法出窖 ${it.qty} 瓶`)
      return
    }
  }
  submitting.value = true
  try {
    await api.createSaleOut(payload)
    ElMessage.success('出窖成功，已按最早入窖批次扣减并留存批次明细')
    drawer.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
  finally { submitting.value = false }
}

async function refund(o) {
  try {
    await ElMessageBox.confirm(
      `确认退窖订单 #${o.id}？将按原出窖批次逐项回补瓶数、恢复批次状态${o.memberId ? '并回滚会员积分' : ''}`,
      '退窖确认', { type: 'warning' }
    )
    await api.refundSaleOut(o.id)
    ElMessage.success('已退窖：批次瓶数、批次状态、酒款库存均已回补')
    await load(); emit('changed')
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <span class="hint">仅通过可售资格（在售 + 账实一致 + 有可售批次瓶数）的酒款可出窖；同一酒款按最早入窖批次优先（FIFO）扣减，实际批次逐行留存</span>
      <el-button type="primary" @click="openCreate">新建出窖</el-button>
    </div>

    <el-table :data="orders" border stripe class="orders" row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div class="detail-title">出窖批次明细（实际扣减来源，退窖按此逐项回补）</div>
            <el-table :data="itemMap[row.id] || []" size="small" border class="detail-table">
              <el-table-column label="酒款" min-width="160">
                <template #default="{ row: r }">
                  {{ wineMap[r.wineId]?.code }} {{ wineMap[r.wineId]?.name || '未知酒款' }}
                </template>
              </el-table-column>
              <el-table-column label="扣减批次" min-width="140">
                <template #default="{ row: r }">
                  <span class="batch-link">{{ batchMap[r.batchId]?.batchNo || `批次#${r.batchId}` }}</span>
                  <span class="batch-date" v-if="batchMap[r.batchId]">入窖 {{ batchMap[r.batchId].cellarDate }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="qty" label="扣减瓶数" width="100" />
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="单号" width="80" />
      <el-table-column label="会员" min-width="120">
        <template #default="{ row }">
          <span v-if="row.memberId">{{ memberMap[row.memberId]?.name || '会员' }}</span>
          <el-tag v-else size="small" type="info">散客</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总额" width="120">
        <template #default="{ row }"><span class="total">¥{{ row.total }}</span></template>
      </el-table-column>
      <el-table-column label="本单积分" width="100">
        <template #default="{ row }">{{ row.pointsEarned ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === '已出窖' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.status === '已出窖'" size="small" type="danger" @click="refund(row)">退窖</el-button>
          <span v-else class="muted">已退窖</span>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" title="出窖收银台" size="520px">
      <el-form label-width="70px">
        <el-form-item label="关联会员">
          <el-select v-model="memberId" clearable placeholder="散客（不选）" style="width:100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.level} · ${m.points}分）`" :value="m.id" />
          </el-select>
        </el-form-item>

        <el-divider>出窖明细</el-divider>
        <div v-for="(it, i) in items" :key="i" class="item-block">
          <div class="item-row">
            <el-select v-model="it.wineId" filterable placeholder="仅可选择通过可售资格核验的酒款" style="flex:1" @change="onWineChange(it)">
              <el-option
                v-for="w in sellableWines" :key="w.id"
                :label="`${w.code} ${w.name}（¥${w.price}/可售${availableOf(w.id)}瓶）`"
                :value="w.id"
              />
            </el-select>
            <el-input-number v-model="it.qty" :min="1" :max="maxFor(it)" />
            <el-button v-if="items.length > 1" text type="danger" @click="removeItem(i)">删</el-button>
          </div>
          <div v-if="it.wineId" class="batch-preview">
            <template v-if="previewMap[i]?.length">
              <span class="bp-label">将扣批次：</span>
              <el-tag
                v-for="(p, j) in previewMap[i]" :key="j" size="small"
                :type="p.short ? 'danger' : 'warning'" effect="plain" class="bp-tag"
              >{{ p.batchNo }} ×{{ p.qty }}</el-tag>
            </template>
            <div v-else class="bp-empty">该酒款暂无可售批次瓶数</div>
          </div>
        </div>
        <el-button link type="primary" @click="addItem">+ 增加一行</el-button>

        <div class="total-line">合计：<strong>¥{{ total.toFixed(2) }}</strong></div>
        <div class="points-line" v-if="memberId">预计获得积分 {{ Math.floor(total) }} 分，退窖时自动回滚</div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确认出窖</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; gap: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.orders { border-radius: 10px; }
.total { color: var(--primary); font-weight: 700; }
.muted { color: var(--muted); font-size: 12px; }
.detail { padding: 8px 20px 14px; background: #faf7f8; }
.detail-title { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.detail-table { background: #fff; }
.batch-link { font-weight: 700; color: var(--primary-deep); }
.batch-date { margin-left: 8px; color: var(--muted); font-size: 12px; }
.item-block { margin-bottom: 12px; }
.item-row { display: flex; gap: 8px; align-items: center; }
.batch-preview { margin-top: 6px; padding: 6px 8px; background: #faf4f6; border-radius: 8px; font-size: 12px; }
.bp-label { color: var(--muted); }
.bp-tag { margin: 2px 4px 2px 0; }
.bp-empty { color: var(--muted); }
.total-line { margin-top: 14px; font-size: 15px; text-align: right; color: var(--ink); }
.total-line strong { color: var(--primary); }
.points-line { margin-top: 6px; font-size: 12px; color: var(--muted); text-align: right; }
</style>
