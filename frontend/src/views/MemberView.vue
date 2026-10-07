<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const LEVELS = ['普通', '银卡', '黑卡']
const LEVEL_TAG = { '普通': 'info', '银卡': 'warning', '黑卡': 'danger' }
const FILTERS = ['全部', ...LEVELS]

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
const form = reactive({ phone: '', name: '', level: '普通', points: 0 })

function openCreate() {
  editing.value = null
  Object.assign(form, { phone: '', name: '', level: '普通', points: 0 })
  dialog.value = true
}
function openEdit(m) {
  editing.value = m.id
  Object.assign(form, { phone: m.phone, name: m.name, level: m.level, points: m.points })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) { await api.updateMember(editing.value, { ...form }) }
    else { await api.createMember({ ...form }) }
    ElMessage.success('已保存会员')
    dialog.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

// ---------------- 加积分 ----------------
async function addPoints(m) {
  try {
    const { value } = await ElMessageBox.prompt('本次增加积分（负数为扣减）', `会员 ${m.name}`, {
      inputType: 'number', inputValue: 100, confirmButtonText: '确定', cancelButtonText: '取消'
    })
    const delta = parseInt(value, 10)
    if (Number.isNaN(delta)) return
    await api.addMemberPoints(m.id, delta)
    ElMessage.success('积分已更新')
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

    <el-dialog v-model="dialog" :title="editing ? '编辑会员' : '新增会员'" width="420px">
      <el-form label-width="70px">
        <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="等级">
          <el-select v-model="form.level"><el-option v-for="l in LEVELS" :key="l" :label="l" :value="l" /></el-select>
        </el-form-item>
        <el-form-item label="积分"><el-input-number v-model="form.points" :min="0" /></el-form-item>
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
</style>
