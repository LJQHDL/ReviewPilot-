import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 120000
})

export async function review(prUrl) {
  const { data } = await client.post('/review', { prUrl })
  return data
}

export async function health() {
  const { data } = await client.get('/health')
  return data
}
