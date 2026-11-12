/** Reject unsafe JSON integers before they can be sent back rounded by the browser. */
export function parsePointJson(text: string): unknown {
  const value: unknown = JSON.parse(text)
  function check(v: unknown): void {
    if (typeof v === 'number' && Number.isInteger(v) && !Number.isSafeInteger(v)) {
      throw new Error('整数超过浏览器安全范围，请使用十进制字符串及 decimal_string 编码')
    }
    if (Array.isArray(v)) v.forEach(check)
    else if (v && typeof v === 'object') Object.values(v).forEach(check)
  }
  check(value)
  return value
}

export function parsePointObject(text: string): Record<string, unknown> {
  const value = parsePointJson(text)
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('请填写 JSON 点位对象')
  return value as Record<string, unknown>
}
