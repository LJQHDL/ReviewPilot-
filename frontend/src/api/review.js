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
      error.message ||
      '请求失败'
    return Promise.reject(new Error(reason))
  }
)

const PR_URL_RE = /^https?:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+\/?$/

export function isValidPrUrl(url) {
  return typeof url === 'string' && PR_URL_RE.test(url.trim())
}

export async function review(prUrl) {
  const { data } = await client.post('/review', { prUrl })
  return data
}

export async function health() {
  const { data } = await client.get('/health')
  return data
}
