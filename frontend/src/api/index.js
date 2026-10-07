import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 酒款 wine ----------------
  listWines: (region, type, status) => call(http.get('/wines', params(
    Object.fromEntries(
      Object.entries({ region, type, status }).filter(([, v]) => v !== undefined && v !== null && v !== '')
    )
  ))),
  createWine: (d) => call(http.post('/wines', d)),
  updateWine: (id, d) => call(http.put(`/wines/${id}`, d)),
  reconcileWineStock: (id) => call(http.post(`/wines/${id}/reconcile-stock`)),
  // 停售/复售（含在途业务与批次资格保护，状态只能经此流转）
  delistWine: (id) => call(http.post(`/wines/${id}/delist`)),
  relistWine: (id) => call(http.post(`/wines/${id}/relist`)),
  wineSaleEligibility: (id) => call(http.get(`/wines/${id}/sale-eligibility`)),

  // ---------------- 窖藏批次 cellar_batch ----------------
  listCellarBatches: (status) => call(http.get('/cellar-batches', params(status ? { status } : {}))),
  createCellarBatch: (d) => call(http.post('/cellar-batches', d)),
  updateCellarBatch: (id, d) => call(http.put(`/cellar-batches/${id}`, d)),
  releaseCellarBatch: (id) => call(http.post(`/cellar-batches/${id}/release`)),

  // ---------------- 会员 member ----------------
  listMembers: (level) => call(http.get('/members', params(level ? { level } : {}))),
  createMember: (d) => call(http.post('/members', d)),
  updateMember: (id, d) => call(http.put(`/members/${id}`, d)),
  addMemberPoints: (id, delta) => call(http.post(`/members/${id}/points?delta=${delta}`)),

  // ---------------- 销售出窖 sale_out ----------------
  listSaleOuts: (status) => call(http.get('/sale-outs', params(status ? { status } : {}))),
  listSaleOutItems: (id) => call(http.get(`/sale-outs/${id}/items`)),
  createSaleOut: (d) => call(http.post('/sale-outs', d)),
  refundSaleOut: (id) => call(http.post(`/sale-outs/${id}/refund`))
}
