package com.cayleywcs.simulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存设备仿真基类（M9）：固化点位存储、PLC 心跳翻转、故障注入/复位与快照；
 * 子类只实现 {@link #onSet}（命令副作用）与 {@link #onTick}（节拍推进）。所有读写经 synchronized 保证线程安全。
 */
public abstract class AbstractDeviceSim implements DeviceSim {
    protected final Map<String, Object> store = new ConcurrentHashMap<>();
    private long plcHeart = 0;

    @Override
    public synchronized void tick() {
        plcHeart = plcHeart == 0 ? 1 : 0;
        store.put("PLC_Heart", plcHeart);
        onTick();
    }

    @Override
    public synchronized Object get(String field) {
        return store.getOrDefault(field, 0);
    }

    @Override
    public synchronized void set(String field, Object value) {
        store.put(field, value);
        int iv = toInt(value);
        // 通用故障复位：cmd_ResetFault=1 清故障码并中止当前动作回到空闲（模拟 PLC 异常处理/解警）。
        // 复位任务态是关键：否则故障失败的任务会把设备永久卡在非空闲态，后续任务在 check() 永远等不到 status_Task==0。
        if ("cmd_ResetFault".equals(field) && iv == 1) {
            store.put("status_ErrorCode", 0);
            if (store.containsKey("status_Task")) {
                store.put("status_Task", 0);
            }
        }
        onSet(field, iv);
    }

    @Override
    public synchronized void injectFault(int code) {
        store.put("status_ErrorCode", code);
    }

    @Override
    public synchronized void clearFault() {
        store.put("status_ErrorCode", 0);
    }

    @Override
    public synchronized void reset() {
        store.clear();
        plcHeart = 0;
        store.put("PLC_Heart", 0);
        seed();
    }

    @Override
    public synchronized Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }

    /** 初始/复位点位与内部计数（构造与 reset 共用；子类在此 put 初值并清零自身计数）。 */
    protected abstract void seed();

    /** 命令写入副作用（已在 synchronized 内）。intValue 为 value 的整数解释，便于判定。 */
    protected abstract void onSet(String field, int intValue);

    /** 节拍推进（已在 synchronized 内，PLC 心跳已翻转）。 */
    protected abstract void onTick();

    protected int intVal(String field) {
        return toInt(store.get(field));
    }

    protected static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
