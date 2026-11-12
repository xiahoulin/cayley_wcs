import json,pathlib,urllib.request,urllib.error,time,secrets,sys,subprocess
from datetime import datetime
from zoneinfo import ZoneInfo
import argparse,os
parser=argparse.ArgumentParser(description="当前Docker的HTTP点位合同→OPC UA堆垛机→WMS业务仿真；保留数据，凭据仅存仓库外目录")
parser.add_argument('--stage',required=True,choices=['setup','public','joint','capture'])
parser.add_argument('--work-dir',required=True)
parser.add_argument('--wms-url',default='http://127.0.0.1:20011')
parser.add_argument('--wcs-url',default='http://127.0.0.1:20021')
parser.add_argument('--callback-base',default='http://host.docker.internal:20011')
parser.add_argument('--wcs-docker-url',default='http://host.docker.internal:20021')
parser.add_argument('--prefix',required=True,help='本次独立数据前缀，仅字母、数字和连字符；重跑使用相同前缀和工作目录')
args=parser.parse_args()
if not args.prefix or any(c not in 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-' for c in args.prefix):parser.error('prefix只能含字母、数字和连字符')
if not os.environ.get('CAYLEY_WCS_TEST_PASSWORD'):parser.error('必须显式设置CAYLEY_WCS_TEST_PASSWORD；不会写入仓库')
OUT=pathlib.Path(args.work_dir).resolve()
if OUT.is_relative_to(pathlib.Path(__file__).resolve().parents[3]):parser.error('工作目录必须在cayley_ai仓库外，避免凭据进入仓库')
OUT.mkdir(parents=True,exist_ok=True)
BASE={'wcs':args.wcs_url.rstrip('/'),'wms':args.wms_url.rstrip('/')}
STAGE=args.stage
HEAD={}
def save(name,data): (OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def load(name): return json.loads((OUT/name).read_text(encoding='utf-8'))
def post(side,path,body=None,public=False):
    req=urllib.request.Request(BASE[side]+path,json.dumps(body or {}).encode(),{'Content-Type':'application/json',**({} if public else HEAD.get(side,{}))},method='POST')
    try:
        with urllib.request.urlopen(req,timeout=40) as r:return json.load(r)
    except urllib.error.HTTPError as e:return json.load(e)
def ok(side,path,body=None):
    r=post(side,path,body)
    if not r.get('isSuccess',r.get('success',False)):raise RuntimeError(path+': '+str(r.get('code'))+' '+str(r.get('message',r.get('errorMessage'))))
    return r['data']
def login():
    for side in BASE:
        r=post(side,'/login',{'user_name':os.environ.get('CAYLEY_WCS_TEST_USER','admin'),'password':os.environ['CAYLEY_WCS_TEST_PASSWORD']},True)
        HEAD[side]={'Authorization':'Bearer '+r['data']['access_token']}
def scrub(x):
    if isinstance(x,dict): return {k:('APP_KEY_EXAMPLE' if k=='app_key' else 'APP_SECRET_EXAMPLE' if k in ('app_secret','secret') else 'ACCESS_TOKEN_EXAMPLE' if k=='access_token' else scrub(v)) for k,v in x.items() if k!='tenant_id'}
    if isinstance(x,list):return [scrub(v) for v in x]
    return x
def point_payload(kind=4):
    return dict(cmd_TaskType=kind,cmd_TakeCoor_Row=1,cmd_TakeCoor_Column=1,cmd_TakeCoor_Floor=1,cmd_PutCoor_Row=1 if kind in (1,3) else 0,cmd_PutCoor_Column=2 if kind in (1,3) else 0,cmd_PutCoor_Floor=1 if kind in (1,3) else 0,cmd_PortNum=1 if kind in (1,2) else 0)
def public_state():
    state=load('private-state.json');a=state['caller'];body=dict(app_id=str(a['id']),app_key=a['app_key'],app_secret=a['app_secret'],grant_type='client_credentials')
    issued=post('wcs','/system/token',body,True);assert issued['errcode']==0
    return state,body,issued
def envelope(a,token,data,request_id='POINT-CONTRACT-20251013'):
    return dict(app_id=str(a['id']),app_key=a['app_key'],access_token=token,request_id=request_id,data=data)
def pub(path,a,token,data):return post('wcs',path,envelope(a,token,data),True)
def wait_remote(id,a,token):
    end=time.monotonic()+100
    while time.monotonic()<end:
        rows=pub('/open/task/query',a,token,dict(appId=3,sinceMillis=0,limit=2000))['data']['rows'];t=next(x for x in rows if x['id']==id)
        if t['status']=='completed':return t
        if t['status']=='failed':raise RuntimeError('remote task failed '+str(t.get('error_code')))
        time.sleep(.5)
    admin=ok('wcs','/task/detail',{'id':id});raise RuntimeError('remote task timeout '+str({k:admin.get(k) for k in ('status','handshake_step','held_reason','error_code')}))
def wait_wms(id):
    end=time.monotonic()+150
    while time.monotonic()<end:
        t=ok('wms','/wcs/task/detail',{'id':id})
        if t['status']=='completed':return t
        if t['status'] in ('failed','biz_failed','cancelled'):raise RuntimeError('WMS task '+str(id)+' '+str(t['status'])+' '+str(t.get('error_message')))
        time.sleep(1)
    raise RuntimeError('WMS task timeout '+str({k:t.get(k) for k in ('id','status','error_message','wcs_task_id')}))
def complete_workflow(order,prefix):
    workflow=order['workflow']
    if workflow['mode']=='DIRECT':return
    for step in workflow['steps']:
        ok('wms','/workflow/execute-step-batch',dict(workflow_instance_id=workflow['id'],step_sequence=step['step_sequence'],execution_parameter='点位合同仿真',idempotency_key=prefix+'-'+str(step['step_sequence']),lines=[dict(workflow_line_id=line['id'],qty=line['planned_qty']) for line in workflow['lines']]))
login()
if STAGE=='setup':
    profiles=ok('wcs','/protocol/all')
    selected={}
    for code in ('HTTP_DEVICE_CONTRACT','PALLET_SIM_OPCUA'):
        p=next(p for p in profiles if p['protocol_code']==code)
        if p['lifecycle_state']!='published':p=ok('wcs','/protocol/publish',dict(id=p['id'],row_version=p['row_version']))
        selected[code]=p
    apps=ok('wcs','/application/all');stacker=next(a for a in apps if a['id']==3)
    if stacker['app_code']!='PALLET_SIM' or stacker['protocol_id']!=selected['PALLET_SIM_OPCUA']['id']:
        raise RuntimeError('仅允许已知内置Milo仿真应用，禁止运行在真实设备上')
    params=stacker.get('conn_params') or {}
    address=params.get('plc4xConnectionString') or params.get('endpoint') or ''
    if not (address.startswith('opcua:tcp://127.0.0.1:4840/') or address.startswith('opc.tcp://127.0.0.1:4840/')):
        raise RuntimeError('仿真应用必须使用容器内127.0.0.1:4840端点')
    stacker['protocol_revision_id']=selected['PALLET_SIM_OPCUA']['published_revision_id']
    stacker=ok('wcs','/application/update',stacker)
    caller=next((a for a in apps if a['app_code']==args.prefix+'-CALLER'),None)
    if caller is None:
        caller=ok('wcs','/application/create',dict(app_code=args.prefix+'-CALLER',app_name='点位合同联调应用',direction='upstream',device_type='none',conn_params={},enabled=True))
        profile=ok('wcs','/protocol/clone',dict(id=selected['HTTP_DEVICE_CONTRACT']['id'],app_id=caller['id']))
        profile=ok('wcs','/protocol/publish',dict(id=profile['id'],row_version=profile['row_version']))
        caller.update(protocol_id=profile['id'],protocol_revision_id=profile['published_revision_id'])
        caller=ok('wcs','/application/update',caller)
    match=ok('wcs','/binding/check',dict(upstream_app_id=caller['id'],downstream_app_id=3));assert match['compatible']
    ok('wcs','/binding/grant',dict(upstreamAppId=caller['id'],downstreamAppIds=[3],scope='dispatch'))
    if not any(c['appId']==3 for c in ok('wcs','/connection/status')['connections']):ok('wcs','/connection/open',{'id':3})
    secret=secrets.token_urlsafe(32)
    server_id=ok('wms','/wcs/server/save',dict(server_code=args.prefix,server_name='点位合同实际OPC UA联调',adapter_type='cayley',base_url=args.wcs_docker_url,app_id=str(caller['id']),app_key=caller['app_key'],app_secret=caller['app_secret'],webhook_secret=secret,sign_version=2,enabled=True))
    hook=ok('wcs','/webhook/create',dict(name=args.prefix,url=args.callback_base.rstrip('/')+'/wcs/callback/'+args.prefix,secret=secret,event_types=['task.completed','task.failed','task.cancelled','chain.completed','chain.failed','chain.cancelled','alarm.raised','alarm.cleared'],scope_app_ids=[3,caller['id']],enabled=True))
    save('private-state.json',dict(caller=caller,stacker=stacker,profiles=selected,server_id=server_id,hook=hook,webhook_secret=secret))
    print(json.dumps(dict(setup='passed',caller_id=caller['id'],stacker_id=3,server_id=server_id,published_revision_ids={k:v['published_revision_id'] for k,v in selected.items()})))
elif STAGE=='public':
    state,token_req,issued=public_state();a=state['caller'];token=issued['data']['access_token'];examples={'requests':{'/system/token':scrub(token_req)},'responses':{'/system/token':scrub(issued)},'callbacks':{},'app_id':str(a['id']),'target_app_id':3}
    data=dict(appId=3,taskNo='POINT-PAYLOAD-20251013-'+secrets.token_hex(3),priority=0,wmsRef='POINT-CONTRACT-20251013',payload=point_payload())
    first=pub('/open/task/dispatch',a,token,data);assert first['errcode']==0
    examples['requests']['/open/task/dispatch']=scrub(envelope(a,token,data));examples['responses']['/open/task/dispatch']=first
    repeat=pub('/open/task/dispatch',a,token,data);assert repeat['data']['id']==first['data']['id']
    changed=dict(data,payload=point_payload(2));assert pub('/open/task/dispatch',a,token,changed)['errcode']==409
    bad=dict(data,taskNo='BAD-'+secrets.token_hex(3),taskType='1');assert pub('/open/task/dispatch',a,token,bad)['errcode']==4241
    bad=dict(data,taskNo='BAD-'+secrets.token_hex(3),payload=dict(point_payload(),cmd_ConfirmTask=1));assert pub('/open/task/dispatch',a,token,bad)['errcode']==4241
    final=wait_remote(first['data']['id'],a,token);assert final['result']['payload']['status_Task']==2 and len(final['result']['payload']['status_ErrorCode'])==60
    chain_data=dict(chainNo='POINT-CHAIN-20251013-'+secrets.token_hex(3),priority=0,selectStrategy='idle_first',wmsRef='POINT-CONTRACT-20251013',payload={},segments=[dict(seq=1,appId=3,payload=point_payload(4)),dict(seq=2,appId=3,payload=point_payload(4))])
    chain=pub('/open/chain/dispatch',a,token,chain_data);assert chain['errcode']==0
    examples['requests']['/open/chain/dispatch']=scrub(envelope(a,token,chain_data));examples['responses']['/open/chain/dispatch']=chain
    until=time.monotonic()+120
    while time.monotonic()<until:
        chain_rows=pub('/open/chain/query',a,token,dict(sinceMillis=0,limit=2000))['data']['rows'];c=next(x for x in chain_rows if x['id']==chain['data']['id'])
        if c['status']=='completed':break
        if c['status'] in ('failed','cancelled'):raise RuntimeError('chain failed '+str(c.get('error_message')))
        time.sleep(1)
    else:raise RuntimeError('chain timeout')
    for path,q in [('/open/task/query',dict(appId=3,sinceMillis=0,limit=2000)),('/open/alarm/query',dict(appId=3,sinceMillis=0,limit=20)),('/open/snapshot',dict(appId=3)),('/open/chain/query',dict(sinceMillis=0,limit=2000))]:
        examples['requests'][path]=scrub(envelope(a,token,q));examples['responses'][path]=pub(path,a,token,q);assert examples['responses'][path]['errcode']==0
    examples['points']=ok('wcs','/protocol/revisions',dict(id=state['profiles']['PALLET_SIM_OPCUA']['id']))[-1]['points']
    save('doc-live-examples.json',scrub(examples));save('public-evidence.json',dict(checks=['published_http_matches_opcua','token_flow','payload_dispatch','idempotent_repeat','changed_request_409','legacy_flat_4241','internal_point_4241','milo_completion_result_before_clear','stacker_chain_two_segments'],task=final,chain=c))
    print(json.dumps(dict(public='passed',task_id=final['id'],chain_id=c['id'],result_quality=final['result']['quality'],native_feedback=final['result']['payload']['status_TaskNum'])))
elif STAGE=='joint':
    state=load('private-state.json');f=load('joint-fixtures.json') if (OUT/'joint-fixtures.json').exists() else {}
    def checkpoint():save('joint-fixtures.json',f)
    dictionaries=ok('wms','/dictionary/list')
    def dictionary(code,value=None):return next(i['id'] for d in dictionaries if d['dict_code']==code for i in d['items'] if i.get('is_valid') and (value is None or str(i['item_value'])==value or str(i.get('item_code'))==value))
    if 'warehouse' not in f:
        f['warehouse']=ok('wms','/warehouse',dict(warehouse_code=args.prefix,warehouse_name='点位合同堆垛机联调仓',physical_layout_enabled=False,is_valid=True));checkpoint()
    w=f['warehouse']
    if 'zone' not in f:f['zone']=ok('wms','/warehousearea',dict(warehouse_id=w['id'],warehouse_area_code=args.prefix+'-ZONE',area_name='仿真库区',zone_type_item_id=dictionary('WAREHOUSE_ZONE_TYPE','STORAGE'),physical_layout_enabled=False,is_valid=True));checkpoint()
    z=f['zone']
    if 'aisle' not in f:f['aisle']=ok('wms','/warehouseaisle',dict(warehouse_id=w['id'],warehouse_area_id=z['id'],aisle_no='1',physical_layout_enabled=False,is_valid=True));checkpoint()
    if 'rack' not in f:f['rack']=ok('wms','/warehouserack',dict(warehouse_id=w['id'],warehouse_area_id=z['id'],aisle_id=f['aisle']['id'],rack_no='1',physical_layout_enabled=False,is_valid=True));checkpoint()
    for n in (1,2):
        key='location'+str(n)
        if key not in f:f[key]=ok('wms','/goodslocation',dict(warehouse_id=w['id'],warehouse_area_id=z['id'],placement_mode='RACK',rack_id=f['rack']['id'],location_name=args.prefix+'-1-'+str(n)+'-1',layer_number='1',tag_number=str(n),physical_layout_enabled=False,is_valid=True));checkpoint()
    if 'device_id' not in f:
        f['device_id']=ok('wms','/wcs/device/save',dict(server_id=state['server_id'],aisle_id=f['aisle']['id'],inbound_location_id=f['location1']['id'],device_name='真实Milo堆垛机仿真',wcs_app_id=3,inbound_port=1,outbound_port=1,max_row=10,max_column=10,max_floor=10,enabled=True));checkpoint()
    if 'sku' not in f:
        f['sku']=next(x for x in ok('wms','/spu/list',dict(pageIndex=1,pageSize=200))['rows'] if x.get('sku_id'));f['owner']=ok('wms','/goodsowner/all')[0];f['supplier']=ok('wms','/supplier/all')[0];f['customer']=ok('wms','/customer/all')[0];checkpoint()
    lot=args.prefix+'-LOT'
    if 'container' not in f:f['container']=ok('wms','/container/create',dict(lpn_code=args.prefix+'-LPN',container_name='点位合同仿真托盘',container_type_item_id=dictionary('CONTAINER_TYPE')));checkpoint()
    if 'inbound' not in f:
        f['inbound']=ok('wms','/inbound/save',dict(asn_no=args.prefix+'-IN',warehouse_id=w['id'],inbound_type_item_id=dictionary('INBOUND_TYPE'),inbound_date='2025-10-13',supplier_id=f['supplier']['id'],goods_owner_id=f['owner']['id'],default_lot_code=lot,remark='HTTP点位到OPC UA实际仿真',lines=[dict(sku_id=f['sku']['sku_id'],planned_qty=2,lot_code=lot,cost_price=10,unit_weight=1,unit_volume=1)]));checkpoint()
        complete_workflow(f['inbound'],args.prefix+'-IN-STEP')
    if 'putaway_id' not in f:
        line=f['inbound']['lines'][0];ids=ok('wms','/wcs/task/create-putaway',dict(asn_id=line['id'],rows=[dict(workflow_line_id=line['workflow_line_id'],goods_location_id=f['location1']['id'],series_number=lot,putaway_qty=2,direct_container_id=f['container']['id'],selected_container_id=f['container']['id'])]));f['putaway_id']=ids[0];checkpoint()
    f['putaway']=wait_wms(f['putaway_id']);checkpoint()
    inbound=ok('wms','/inbound/detail',dict(id=f['inbound']['id']));assert inbound['lines'][0]['posted_qty']==2
    if 'move_task_id' not in f:f['move_task_id']=ok('wms','/wcs/task/create-root-move',dict(selected_container_id=f['container']['id'],dest_goods_location_id=f['location2']['id'],effect_mode='MOVE_ROOT_TO_LOCATION'));checkpoint()
    f['move']=wait_wms(f['move_task_id']);checkpoint()
    if 'outbound' not in f:
        balances=ok('wms','/stock/select',dict(pageIndex=1,pageSize=100,searchObjects=[dict(name='series_number',operator=1,value=lot)]))['rows']
        moved=next(b for b in balances if b['goods_location_id']==f['location2']['id'] and b['qty_available']==2)
        f['outbound']=ok('wms','/outbound/save',dict(dispatch_no=args.prefix+'-OUT',warehouse_id=w['id'],outbound_type_item_id=dictionary('OUTBOUND_TYPE'),outbound_date='2025-10-13',customer_id=f['customer']['id'],remark='真实OPC UA点位出库仿真',lines=[dict(balance_id=moved['balance_id'],planned_qty=2)]));checkpoint()
        complete_workflow(f['outbound'],args.prefix+'-OUT-STEP')
    if 'pick_id' not in f:
        ids=ok('wms','/wcs/task/create-pick',dict(dispatch_no=f['outbound']['dispatch_no'],dispatch_line_id=f['outbound']['lines'][0]['id'],selected_container_id=f['container']['id']));f['pick_id']=ids[0];checkpoint()
    f['pick']=wait_wms(f['pick_id']);checkpoint()
    outbound=ok('wms','/outbound/detail',dict(id=f['outbound']['id']));assert outbound['lines'][0]['posted_qty']==2
    state,_,issued=public_state();a=state['caller'];token=issued['data']['access_token']
    rows=pub('/open/task/query',a,token,dict(appId=3,sinceMillis=0,limit=2000))['data']['rows']
    remotes=[next(r for r in rows if r['id']==f[k]['wcs_task_id']) for k in ('putaway','move','pick')]
    assert [r['payload']['cmd_TaskType'] for r in remotes]==[1,3,2]
    assert all(r['result']['quality']=='good' and r['result']['payload']['status_Task']==2 for r in remotes)
    assert all(f[k]['completed_by'] in ('auto','reconcile') for k in ('putaway','move','pick'))
    save('joint-evidence.json',scrub(dict(checks=['real_docker_token_and_new_payload','wms_inbound_2_posted','wms_move_entire_container','wms_outbound_2_posted','milo_opcua_three_handshakes','persistent_matching_final_results'],inbound_id=inbound['id'],outbound_id=outbound['id'],warehouse_id=w['id'],container_id=f['container']['id'],wms_tasks={k:f[k] for k in ('putaway','move','pick')},remote_tasks=remotes)))
    print(json.dumps(dict(joint='passed',warehouse_id=w['id'],inbound_posted=2,outbound_posted=2,wms_task_ids=[f[k]['id'] for k in ('putaway','move','pick')],remote_task_ids=[r['id'] for r in remotes],completed_by=[f[k]['completed_by'] for k in ('putaway','move','pick')])))
elif STAGE=='capture':
    state,token_req,issued=public_state();a=state['caller'];token=issued['data']['access_token'];examples=load('doc-live-examples.json');public=load('public-evidence.json')
    # 空闲设备的故障注入仅验证报警与响应数组，不创建任何业务任务。
    ok('wcs','/simulator/inject-fault',dict(appId=999001,code=8));time.sleep(4)
    alarm_rows=pub('/open/alarm/query',a,token,dict(appId=3,sinceMillis=0,limit=2000))['data']['rows'];alarm=next(x for x in reversed(alarm_rows) if x['fault_code']==8)
    ok('wcs','/simulator/clear-fault',dict(appId=999001,code=0));time.sleep(4)
    def watermark(row):return int(datetime.fromisoformat(row['last_update_time']).replace(tzinfo=ZoneInfo('Asia/Tokyo')).timestamp()*1000)
    task=public['task'];chain=public['chain']
    for path,q in [('/open/task/query',dict(appId=3,sinceMillis=watermark(task),limit=1)),('/open/alarm/query',dict(appId=3,sinceMillis=watermark(alarm),limit=1)),('/open/chain/query',dict(sinceMillis=watermark(chain),limit=1)),('/open/snapshot',dict(appId=3))]:
        response=pub(path,a,token,q);assert response['errcode']==0
        examples['requests'][path]=scrub(envelope(a,token,q));examples['responses'][path]=response
    deliveries=ok('wcs','/webhook/deliveries',dict(webhookId=state['hook']['id'],limit=100))
    for kind in ('task','chain','alarm'):
        delivery=next(x for x in deliveries if x['event_type'].startswith(kind+'.'))
        value=delivery['payload'];examples['callbacks'][kind]=json.loads(value) if isinstance(value,str) else value
    examples['fault_codes']=ok('wcs','/fault-code/list',dict(id=state['profiles']['PALLET_SIM_OPCUA']['id']))
    if not examples['fault_codes']:
        profiles=ok('wcs','/protocol/all');source=next(p for p in profiles if p['protocol_code']=='STACKER_STD');examples['fault_codes']=ok('wcs','/fault-code/list',dict(id=source['id']))
    assert len(examples['callbacks'])==3 and examples['fault_codes']
    examples['requests']['/system/token']=scrub(token_req);examples['responses']['/system/token']=scrub(issued)
    save('doc-live-examples.json',scrub(examples));save('callback-evidence.json',scrub(dict(task=examples['callbacks']['task'],chain=examples['callbacks']['chain'],alarm=examples['callbacks']['alarm'],delivery_statuses=[dict(id=d['id'],event_type=d['event_type'],status=d['status'],last_response=d['last_response']) for d in deliveries])))
    print(json.dumps(dict(document_examples='passed',routes=len(examples['requests']),callbacks=list(examples['callbacks']),fault_codes=len(examples['fault_codes']))))
