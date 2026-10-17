import assert from 'node:assert/strict'
import { test } from 'node:test'
import { riskKey } from './risk.js'

test('riskKey is stable for the same risk content', () => {
  const risk = { file: 'a/A.java', line: 12, message: 'boom' }
  assert.equal(riskKey(risk), 'a/A.java|12|boom')
  assert.equal(riskKey(risk), riskKey({ ...risk }))
})

test('riskKey tolerates missing fields', () => {
  assert.equal(riskKey({}), '|0|')
  assert.equal(riskKey(undefined), '|0|')
})

test('riskKey distinguishes risks that only differ by line', () => {
  const a = { file: 'a/A.java', line: 1, message: 'm' }
  const b = { file: 'a/A.java', line: 2, message: 'm' }
  assert.notEqual(riskKey(a), riskKey(b))
})
