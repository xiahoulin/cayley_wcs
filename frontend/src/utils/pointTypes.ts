export const logicalPointTypes = [
  'BOOL', 'INT8', 'UINT8', 'INT16', 'UINT16', 'INT32', 'UINT32',
  'INT64', 'UINT64', 'FLOAT32', 'FLOAT64', 'DECIMAL', 'STRING',
]

const typeNames: Record<string, string> = {
  BOOL: '布尔值', INT8: '8 位有符号整数', UINT8: '8 位无符号整数',
  INT16: '16 位有符号整数', UINT16: '16 位无符号整数',
  INT32: '32 位有符号整数', UINT32: '32 位无符号整数',
  INT64: '64 位有符号整数', UINT64: '64 位无符号整数',
  FLOAT32: '32 位浮点数', FLOAT64: '64 位浮点数',
  DECIMAL: '十进制数', STRING: '字符串',
}
const aliases: Record<string, string> = {
  BOOLEAN: 'BOOL', BIT: 'BOOL', TEXT: 'STRING', CHARACTERSTRING: 'STRING', WSTRING: 'STRING',
  SINT: 'INT8', SBYTE: 'INT8', BYTE: 'UINT8', USINT: 'UINT8',
  INT: 'INT16', SHORT: 'INT16', UINT: 'UINT16', USHORT: 'UINT16', WORD: 'UINT16',
  DINT: 'INT32', UDINT: 'UINT32', DWORD: 'UINT32',
  LINT: 'INT64', LONG: 'INT64', INTEGER: 'INT64', ULINT: 'UINT64', ULONG: 'UINT64', LWORD: 'UINT64',
  REAL: 'FLOAT32', FLOAT: 'FLOAT32', LREAL: 'FLOAT64', DOUBLE: 'FLOAT64', NUMBER: 'FLOAT64',
}

export function isArrayPointType(type: string) {
  return /^ARRAY\s*[<:\[]/i.test(type.trim()) || type.trim().endsWith('[]')
}

// Display names only; the backend still validates protocol support and declaration syntax.
export function pointTypeDescription(type: string): string {
  const normalized = type.trim().toUpperCase()
  const fixedArray = normalized.match(/^ARRAY\s*\[([^\]]+)]\s*OF\s*(.+)$/)
  if (fixedArray) return `${pointTypeDescription(fixedArray[2]!)}数组（声明 ${fixedArray[1]}）`
  const array = normalized.match(/^ARRAY\s*[<:]\s*(.+?)>?$/)
  if (array) return `${pointTypeDescription(array[1]!)}数组`
  if (normalized.endsWith('[]')) return `${pointTypeDescription(normalized.slice(0, -2))}数组`
  return typeNames[aliases[normalized] || normalized] || '自定义类型声明'
}
