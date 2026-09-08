import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 120000
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const data = error.response?.data
    const reason =
      (data && typeof data === 'object' && data.error) ||
      (error.code === 'ECONNABORTED' ? '请求超时，后端仍在评审中，请稍后重试' : null) ||
      error.message ||
      '请求失败'
    return Promise.reject(new Error(reason))
  }
)

const PR_URL_RE = /^https?:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+\/?$/

/** 宽容处理用户粘贴：允许缺协议、带锚点/查询串、首尾空格。 */
export function normalizePrUrl(raw) {
  if (typeof raw !== 'string') return ''
  let url = raw.trim()
  if (!url) return ''
  if (!/^https?:\/\//i.test(url)) url = 'https://' + url.replace(/^\/+/, '')
  url = url.split('#')[0].split('?')[0]
  if (url.endsWith('/')) url = url.slice(0, -1)
  return url
}

export function isValidPrUrl(url) {
  return PR_URL_RE.test(url)
}

export async function review(prUrl) {
  const { data } = await client.post('/review', { prUrl })
  return data
}

export async function health() {
  const { data } = await client.get('/health')
  return data
}
