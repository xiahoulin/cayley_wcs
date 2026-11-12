import type { StatePoint } from '../api/deviceState'

const names: Record<string,string> = {
  PLC_Heart:'PLC 心跳', WCS_Heart:'WCS 心跳', status_Mode:'运行模式', status_Task:'设备任务状态',
  status_ErrorCode:'设备故障码', status_TaskTypeFeedback:'任务类型反馈', status_TaskNum:'设备任务号',
  status_CurrentColumnNum:'当前列', status_CurrentFloorNum:'当前层', status_Speed_Walk:'行走速度',
  status_Speed_Lift:'提升速度', status_Speed_Fork:'货叉速度', status_CurrentPos_Walk:'行走位置',
  status_CurrentPos_Lift:'提升位置', status_CurrentPos_Fork:'货叉位置', status_Cargo:'载货状态',
  cmd_TaskType:'任务类型指令', cmd_TaskNum:'下发设备任务号', cmd_ConfirmTask:'任务确认指令', cmd_ResetFault:'故障复位指令',
  cmd_TakeCoor_Row:'取货排', cmd_TakeCoor_Column:'取货列', cmd_TakeCoor_Floor:'取货层',
  cmd_PutCoor_Row:'放货排', cmd_PutCoor_Column:'放货列', cmd_PutCoor_Floor:'放货层', cmd_PortNum:'货口编号',
  cmd_DestStation:'目标工位', cmd_Start:'输送启动指令', status_Position:'当前位置', status_Occupied:'工位占用状态',
  cmd_TargetFloor:'目标楼层', cmd_Go:'提升机运行指令', status_CurrentFloor:'当前楼层', status_DoorState:'门状态',
  cmd_Open:'开门指令', cmd_Close:'关门指令',
}

/** Preserve maintained Chinese names; explain legacy generated PLC aliases in Chinese. */
export function pointDisplayName(point: Pick<StatePoint,'field'|'label'>): string {
  const label=point.label?.trim() || ''
  if ((!/[\u3400-\u9fff]/.test(label) || /^(状态|命令)_/.test(label)) && names[point.field]) return names[point.field]!
  return label || point.field
}
