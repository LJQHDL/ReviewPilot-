import assert from 'node:assert/strict'
import { test } from 'node:test'
import axios from 'axios'

test('runtime access token is attached, replaced, then removed without changing request body', async () => {
  const requests = []
  const original = axios.defaults.adapter
  axios.defaults.adapter = async config => {
    requests.push(config)
    return { data: { summary: 'ok' }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const { review, setAccessToken } = await import('./review.js')
  try {
    setAccessToken('first-token')
    await review('https://github.com/o/r/pull/1')
    setAccessToken('second-token')
    await review('https://github.com/o/r/pull/1')
    setAccessToken('')
    await review('https://github.com/o/r/pull/1')
    assert.equal(requests[0].headers.get('Authorization'), 'Bearer first-token')
    assert.equal(requests[1].headers.get('Authorization'), 'Bearer second-token')
    assert.equal(requests[2].headers.has('Authorization'), false)
    assert.deepEqual(JSON.parse(requests[0].data), { prUrl: 'https://github.com/o/r/pull/1' })
  } finally {
    axios.defaults.adapter = original
    setAccessToken('')
  }
})
