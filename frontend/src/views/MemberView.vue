<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

// 阈值与后端 Dict 保持一致：等级只做展示，从不作为可提交字段
const SILVER_POINTS = 2000
const BLACK_POINTS = 5000
const decideLevel = (points) =>
  points >= BLACK_POINTS ? '黑卡' : points >= SILVER_POINTS ? '银卡' : '普通'
const LEVEL_TAG = { '普通': 'info', '银卡': 'warning', '黑卡': 'danger' }
const FILTERS = ['全部', '普通', '银卡', '黑卡']

const members = ref([])
const loading = ref(false)
const filter = ref('全部')
const shown = computed(() =>
  filter.value === '全部' ? members.value : members.value.filter(m => m.level === filter.value)
)

async function load() {
  loading.value = true
  try { members.value = await api.listMembers() } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 新增 / 编辑 ----------------
const dialog = ref(false)
const editing = ref(null)
// 注意：表单里没有 level 字段；等级由积分自动推导，前端只展示结果
const form = reactive({ phone: '', name: '', points: 0, version: null })
// 编辑时积分调整需显式发起：只改手机号/昵称不会携带 points，避免顺带改写积分与等级
const adjustPoints = ref(false)
const previewLevel = computed(() => decideLevel(Number(form.points) || 0))

function openCreate() {
  editing.value = null
  Object.assign(form, { phone: '', name: '', points: 0, version: null })
  adjustPoints.value = false
  dialog.value = true
}
function openEdit(m) {
  editing.value = m.id
  Object.assign(form, { phone: m.phone, name: m.name, points: m.points, version: m.version })
  adjustPoints.value = false
  dialog.value = true
}
async function submit() {
  const payload = { phone: form.phone, name: form.name }
  if (editing.value) {
    // 乐观锁：携带打开编辑时的版本号，过期提交由后端拒绝
    payload.version = form.version
    if (adjustPoints.value) {
      if (form.points === null || form.points === undefined || Number(form.points) < 0) {
        ElMessage.error('积分不能为负')
        return
      }
      payload.points = Number(form.points)
    }
  } else {
    if (form.points === null || form.points === undefined || Number(form.points) < 0) {
      ElMessage.error('积分不能为负')
      return
    }
    payload.points = Number(form.points)
  }
  try {
    if (editing.value) { await api.updateMember(editing.value, payload) }
    else { await api.createMember(payload) }
    ElMessage.success(adjustPoints.value ? '已保存会员，等级按积分重新核定' : '已保存会员')
    dialog.value = false
    await load(); emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
    // 并发冲突（版本过期）时刷新列表，让用户看到最新积分后重试
    if (editing.value && /过期|刚被其他操作更新|已被其他操作更新/.test(e.message)) {
      await load(); emit('changed')
    }
  }
}

// ---------------- 单独增减积分（delta 入口；等级随同一阈值复核） ----------------
async function addPoints(m) {
  try {
    const { value } = await ElMessageBox.prompt('本次增加积分（负数为扣减，扣后不能为负）', `会员 ${m.name}`, {
      inputType: 'number', inputValue: 100, confirmButtonText: '确定', cancelButtonText: '取消'
    })
    const delta = parseInt(value, 10)
    if (Number.isNaN(delta)) return
    await api.addMemberPoints(m.id, delta)
    ElMessage.success('积分已更新，等级已按阈值复核')
    await load(); emit('changed')
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
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
      <el-button type="primary" @click="openCreate">新增会员</el-button>
    </div>

    <div class="list">
      <div v-for="m in shown" :key="m.id" class="member-card">
        <div class="avatar">{{ m.name.slice(0, 1) }}</div>
        <div class="info">
          <div class="name">
            {{ m.name }}
            <el-tag size="small" :type="LEVEL_TAG[m.level]">{{ m.level }}</el-tag>
          </div>
          <div class="phone">{{ m.phone }}</div>
          <div class="points">积分 <strong>{{ m.points }}</strong></div>
        </div>
        <div class="ops">
          <el-button size="small" @click="addPoints(m)">加积分</el-button>
          <el-button size="small" @click="openEdit(m)">编辑</el-button>
        </div>
      </div>
    </div>

    <el-empty v-if="!loading && shown.length === 0" description="暂无会员" />

    <el-dialog v-model="dialog" :title="editing ? '编辑会员' : '新增会员'" width="440px">
      <el-form label-width="92px">
        <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.name" /></el-form-item>

        <!-- 新增：可录入初始积分，等级实时按积分推导，仅展示不可选 -->
        <el-form-item v-if="!editing" label="初始积分">
          <el-input-number v-model="form.points" :min="0" />
        </el-form-item>
        <el-form-item v-if="!editing" label="核定等级">
          <el-tag size="small" :type="LEVEL_TAG[previewLevel]">{{ previewLevel }}</el-tag>
          <span class="hint">等级由累计积分自动核定，不能手工选择</span>
        </el-form-item>

        <!-- 编辑：默认只改联系方式；勾选后才携带积分，按新积分重设并复核等级 -->
        <template v-else>
          <el-form-item label="当前积分/等级">
            <span class="ro-points">{{ form.points }}</span>
            <el-tag size="small" :type="LEVEL_TAG[decideLevel(Number(form.points) || 0)]" style="margin-left:8px">
              {{ decideLevel(Number(form.points) || 0) }}
            </el-tag>
          </el-form-item>
          <el-form-item label="调整积分">
            <el-switch v-model="adjustPoints" />
            <span class="hint">不勾选时仅保存手机号/昵称，积分与等级保持不变；日常加扣分请用卡片上的「加积分」</span>
          </el-form-item>
          <el-form-item v-if="adjustPoints" label="新积分">
            <el-input-number v-model="form.points" :min="0" />
            <el-tag size="small" :type="LEVEL_TAG[previewLevel]" style="margin-left:8px">
              保存后核定为「{{ previewLevel }}」
            </el-tag>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.list { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.member-card {
  display: flex; align-items: center; gap: 12px;
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px;
}
.avatar {
  width: 42px; height: 42px; border-radius: 50%; flex-shrink: 0;
  background: var(--primary-wash); color: var(--primary-deep);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 18px;
}
.info { flex: 1; min-width: 0; }
.name { font-weight: 700; display: flex; align-items: center; gap: 8px; }
.phone { color: var(--muted); font-size: 12px; margin: 2px 0; }
.points { font-size: 13px; color: var(--ink); }
.points strong { color: var(--primary); }
.ops { display: flex; flex-direction: column; gap: 6px; }
.hint { color: var(--muted); font-size: 12px; margin-left: 8px; }
.ro-points { font-weight: 700; color: var(--primary); }
</style>
