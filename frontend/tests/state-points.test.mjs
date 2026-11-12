import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// Load the pure TS helpers without a browser or an additional test dependency.
async function moduleUrl(name) {
  const source = await readFile(new URL(`../src/utils/${name}.ts`, import.meta.url), 'utf8')
  let js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  for (const match of js.matchAll(/from ['"]\.\/(\w+)['"]/g)) js = js.replace(match[0], `from '${await moduleUrl(match[1])}'`)
  return `data:text/javascript;base64,${Buffer.from(js).toString('base64')}`
}
const { parseStatePointInput: parse, validateStatePointValue: validate, stateSettingPayload: payload, pointQuality } = await import(await moduleUrl('statePoints'))
const point = (type, constraints = {}, extra = {}) => ({ field: 'parameter', type, constraints, writable: true, canSet: true, ...extra })

test('numeric edits preserve zero, reject empty, fractional integers, range overflow and unsafe JSON integers', () => {
  assert.equal(parse(point('UINT16'), '0'), 0)
  assert.equal(parse(point('FLOAT64', { min: -5, max: 5 }), '-1.25'), -1.25)
  for (const input of ['', '1.1', '-1', '65536', '9007199254740993']) assert.throws(() => parse(point('UINT16'), input))
  assert.throws(() => parse(point('FLOAT64', { max: 5 }), '5.1'), /不能大于/)
  assert.throws(() => parse(point('FLOAT32'), '1e50'))
})
test('64-bit integer input is sent as the exact decimal string, never a rounded browser number', () => {
  const p = point('UINT64', { wire_encoding: 'decimal_string', max: '18446744073709551615' })
  assert.equal(parse(p, '18446744073709551615'), '18446744073709551615')
  assert.throws(() => parse(p, '18446744073709551616'))
  assert.throws(() => parse(p, '1.0'))
  assert.throws(() => parse(p, '-1'))
})
test('boolean edits retain their JSON type', () => {
  assert.equal(parse(point('BOOL'), 'false'), false)
  assert.throws(() => parse(point('BOOL'), '0'))
  assert.throws(() => parse(point('BOOL'), '"false"'))
})
test('text preserves whitespace and counts Unicode code points', () => {
  assert.equal(parse(point('STRING'), '  中文\n'), '  中文\n')
  assert.equal(parse(point('STRING', { max_length: 1 }), '🚚'), '🚚')
  assert.throws(() => parse(point('STRING', { max_length: 1 }), '两个'))
  assert.equal(validate(point('UINT16', { enum: [1, 2] }), 2), 2)
  assert.throws(() => validate(point('UINT16', { enum: [1, 2] }), '2'))
  assert.equal(validate(point('STRING', { enum: ['A', 'B'] }), 'B'), 'B')
})
test('arrays validate element types, length and multi-dimensional shape', () => {
  assert.deepEqual(parse(point('ARRAY<INT16>', { dimensions: [2, 2] }), '[[1,2],[3,4]]'), [[1, 2], [3, 4]])
  for (const input of ['[1,2]', '[[1,2],[3]]', '[[1,2],[3,"4"]]']) assert.throws(() => parse(point('ARRAY<INT16>', { dimensions: [2, 2] }), input))
  assert.throws(() => parse(point('ARRAY<INT64>', { max_items: 2 }), '[9007199254740993]'))
  assert.throws(() => parse(point('ARRAY<BOOL>', { length: 2 }), '[true]'))
})
test('one point edit does not submit other parameters; readonly and revoked permissions cannot create a write', () => {
  assert.deepEqual(payload({ deviceType: 'stacker' }, point('UINT16'), 1), { parameter: 1 })
  assert.throws(() => payload({}, point('UINT16', {}, { writable: false }), 1))
  assert.throws(() => payload({}, point('UINT16', {}, { canSet: false }), 1))
})
test('automatic door submits the mutually exclusive pair and refuses a missing or locked partner', () => {
  const open = point('UINT16', {}, { field: 'cmd_Open' }), close = point('UINT16', {}, { field: 'cmd_Close' })
  const state = { deviceType: 'auto_door', points: [open, close] }
  assert.deepEqual(payload(state, open, 1), { cmd_Open: 1, cmd_Close: 0 })
  assert.deepEqual(payload(state, close, 1), { cmd_Close: 1, cmd_Open: 0 })
  assert.throws(() => payload(state, open, 2))
  assert.throws(() => payload({ ...state, points: [open] }, open, 1))
  assert.throws(() => payload({ ...state, points: [open, { ...close, canSet: false }] }, open, 1))
})
test('stale readings age using server time even if the browser clock differs', () => {
  const p = { readable: true, receivedAt: 99000, quality: 'GOOD' }, state = { serverTime: 100000, staleAfterMs: 6000 }
  assert.equal(pointQuality(p, state, 1000, 1000), 'GOOD')
  assert.equal(pointQuality(p, state, 1000, 7001), 'STALE')
})
