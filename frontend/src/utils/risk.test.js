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

test('an expanded content key still points at the same risk after a filter change', () => {
  const risks = [
    { level: 'HIGH', file: 'a/A.java', line: 1, message: 'high' },
    { level: 'MEDIUM', file: 'b/B.java', line: 2, message: 'medium' },
    { level: 'LOW', file: 'c/C.java', line: 3, message: 'low' }
  ]
  const expanded = new Set([riskKey(risks[0])])

  const mediumOnly = risks.filter(r => r.level === 'MEDIUM')
  assert.equal(mediumOnly.some(r => expanded.has(riskKey(r))), false,
    'an index-based key would wrongly mark the first medium row as expanded')

  assert.equal(risks.find(r => expanded.has(riskKey(r))), risks[0])
})
