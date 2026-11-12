package com.cayleywcs.adapter.seer;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import java.util.Map;

/** Application-owned IP overrides only the host of an existing deployment endpoint. */
public final class SeerConnectionSettings {
    private SeerConnectionSettings() { }
    public static void validate(Map<String,Object> params) {
        if (params == null) return;
        Object raw = params.get("ip");
        if (raw != null && (!(raw instanceof String ip) || !validIp(ip)))
            throw new WcsException(4223,"AGV IP 地址须为有效的 IPv4 地址");
        number(params,"statusPort",19204,1,65535);
        number(params,"navigationPort",19206,1,65535);
        number(params,"timeoutMs",1000,100,10000);
    }
    private static boolean validIp(String ip) {
        if (!ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part)>255) return false;
        int first=Integer.parseInt(ip.substring(0,ip.indexOf('.')));
        return first>0 && first<224;
    }
    public static DeviceEndpoints.Endpoint endpoint(ApplicationEntity app,DeviceEndpoints endpoints,boolean navigation) {
        Map<String,Object> params=app.getConn_params()==null?Map.of():app.getConn_params();
        validate(params);
        String ref=String.valueOf(params.getOrDefault("endpointRef",""));
        var legacy=ref.isBlank()?null:endpoints.findForTenant(ref,app.getTenant_id()).orElse(null);
        String ip=(String)params.get("ip");
        if(ip==null && legacy==null) throw new WcsException(4223,"请在仙工 AGV 应用中配置 IP 地址");
        return new DeviceEndpoints.Endpoint(ip!=null?ip:legacy.host(),
                navigation?number(params,"navigationPort",19206,1,65535):number(params,"statusPort",legacy==null?19204:legacy.port(),1,65535),
                number(params,"timeoutMs",legacy==null?1000:legacy.timeoutMs(),100,10000),
                !navigation && legacy!=null && legacy.allowMissingRetCode());
    }
    private static int number(Map<String,Object> params,String key,int fallback,int min,int max) {
        if(!params.containsKey(key)) return fallback;
        try {
            int value=new java.math.BigDecimal(String.valueOf(params.get(key))).intValueExact();
            if(value>=min && value<=max)return value;
        } catch(RuntimeException ignored) { }
        throw new WcsException(4223,"仙工连接参数无效："+key);
    }
}
