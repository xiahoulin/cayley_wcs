"""Read-only production dump, isolated PG restore, actual built-backend Flyway, whitelisted evidence.

Private snapshots/dumps/configuration may contain credentials and never belong in the repository.
This script never stops, updates, or writes SQL into the source container.
"""
import argparse
import hashlib
import json
import pathlib
import re
import secrets
import subprocess
import time
import zipfile

PRIVATE_ERROR_LOG=None


def command(argv, *, stdin=None, stdout_file=None):
    target=open(stdout_file,"wb") if stdout_file else subprocess.PIPE
    try:
        result=subprocess.run(argv,input=stdin,stdout=target,stderr=subprocess.PIPE)
    finally:
        if stdout_file: target.close()
    if result.returncode:
        if PRIVATE_ERROR_LOG is not None:
            with PRIVATE_ERROR_LOG.open("ab") as errors: errors.write(result.stderr)
        raise RuntimeError("Command failed; details retained only in private operation log: "+str(argv[0]))
    return result.stdout if not stdout_file else b""


def sql(container,statement):
    data=command(["docker","exec","-i",container,"sh","-c",
                  'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 -f -'],
                 stdin=statement.encode("utf-8"))
    return data.decode("utf-8").strip()


def snapshot(container):
    tables=json.loads(sql(container,"select coalesce(json_agg(tablename order by tablename),'[]'::json) from pg_tables where schemaname='public';"))
    if any(not re.fullmatch(r"[a-zA-Z0-9_]+",name) for name in tables):
        raise RuntimeError("Unexpected table identifier")
    counts=" union all ".join(f"select '{name}' as name,count(*) as n from public.\"{name}\"" for name in tables)
    result={"counts":json.loads(sql(container,"select json_object_agg(name,n) from ("+counts+") counts;"))}
    result["versions"]=json.loads(sql(container,"select json_agg(version order by installed_rank) from flyway_schema_history where success=true;"))
    for table in ("wcs_task","wcs_task_chain","wcs_application","wcs_protocol","wcs_protocol_point",
                  "wcs_protocol_revision","wcs_app_binding","wcs_dict_type","wcs_dict_item",
                  "wcs_device_task_number","wcs_device_task_number_lease"):
        if table not in tables: continue
        ordering={"wcs_device_task_number":"app_id","wcs_device_task_number_lease":"app_id,native_number"}.get(table,"id")
        result[table]=json.loads(sql(container,f"select coalesce(json_agg(row_to_json(records) order by {ordering}),'[]'::json) from public.\"{table}\" records;"))
    result["point_columns"]=json.loads(sql(container,"select json_agg(column_name order by ordinal_position) from information_schema.columns where table_schema='public' and table_name='wcs_protocol_point';"))
    return result


def save(path,data):
    pathlib.Path(path).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding="utf-8")


def stable_snapshot_equal(left,right):
    # Live polling can append transport logs while the read-only backup runs.
    def stable(value):
        result={key:item for key,item in value.items() if key!="counts"}
        result["counts"]={key:item for key,item in value["counts"].items() if key not in ("wcs_message_log","wcs_connection_log")}
        return result
    return stable(left)==stable(right)


def check_point_field_removal(before,after,field,dictionary,source_version,target_version):
    checks=[]
    def require(name,condition,**details):checks.append({"name":name,"passed":bool(condition),**details})
    def points(row):return json.loads(row["points"]) if isinstance(row["points"],str) else row["points"]
    require(f"flyway_upgraded_{source_version}_to_{target_version}",before["versions"][-1]==source_version and after["versions"]==before["versions"]+[target_version])
    require(f"point_{field}_column_removed",after["point_columns"]==[key for key in before["point_columns"] if key!=field])
    for table in ("wcs_task","wcs_task_chain","wcs_application","wcs_protocol","wcs_app_binding","wcs_device_task_number","wcs_device_task_number_lease"):
        require(table+"_all_rows_and_times_preserved",before[table]==after[table],rows=len(before[table]))
    require("point_all_other_values_and_times_preserved",[{key:value for key,value in row.items() if key!=field} for row in before["wcs_protocol_point"]]==after["wcs_protocol_point"],rows=len(before["wcs_protocol_point"]))
    require(f"point_json_no_{field}",all(field not in row for row in after["wcs_protocol_point"]))
    old_revisions=before["wcs_protocol_revision"];new_revisions=after["wcs_protocol_revision"]
    require("published_revision_ids_preserved",[row["id"] for row in old_revisions]==[row["id"] for row in new_revisions],rows=len(old_revisions))
    require("published_revision_identity_fingerprints_and_times_preserved",[{key:value for key,value in row.items() if key!="points"} for row in old_revisions]==[{key:value for key,value in row.items() if key!="points"} for row in new_revisions])
    require(f"published_snapshot_{field}_keys_removed",all(field not in point for row in new_revisions for point in points(row)))
    require("published_snapshot_other_point_values_preserved",[[{key:value for key,value in point.items() if key!=field} for point in points(row)] for row in old_revisions]==[points(row) for row in new_revisions])
    for table in ("wcs_dict_type","wcs_dict_item"):
        require(table+f"_obsolete_{dictionary}_only_removed",[row for row in before[table] if row["type_code"]!=dictionary]==after[table])
    return checks


def check_application_adapters(before,after):
    checks=[]
    def require(name,condition,**details): checks.append({"name":name,"passed":bool(condition),**details})
    legacy={"seer_tcp":"seer_agv","sensor_gateway":"dr164_sensor"}
    protocols={row["id"]:row for row in before["wcs_protocol"]}
    require("flyway_upgraded_23_to_24",before["versions"][-1]=="23" and after["versions"]==before["versions"]+["24"])
    for table in ("wcs_task","wcs_task_chain","wcs_protocol_point","wcs_app_binding","wcs_dict_type","wcs_device_task_number","wcs_device_task_number_lease"):
        require(table+"_all_rows_and_fields_preserved",before.get(table)==after.get(table),rows=len(before.get(table,[])))
    expected_apps=[]
    for app in before["wcs_application"]:
        expected=dict(app); protocol=protocols[app["protocol_id"]]
        expected["adapter_type"]=legacy.get(protocol["protocol_type"],"standard")
        if protocol["protocol_type"] in legacy: expected["configuration_epoch"]+=1
        expected_apps.append(expected)
    require("application_identity_credentials_configuration_and_runtime_preserved_except_adapter_and_epoch",expected_apps==after["wcs_application"],rows=len(expected_apps))
    expected_protocols=[]
    for protocol in before["wcs_protocol"]:
        expected=dict(protocol)
        if protocol["protocol_type"] in legacy: expected.update(protocol_type="tcp",row_version=protocol["row_version"]+1)
        expected_protocols.append(expected)
    require("protocols_only_reclassify_transport_and_increment_row_version",expected_protocols==after["wcs_protocol"])
    expected_revisions=[dict(row,protocol_type="tcp") if row["protocol_type"] in legacy else row for row in before["wcs_protocol_revision"]]
    require("published_revision_ids_points_fingerprints_and_times_preserved",expected_revisions==after["wcs_protocol_revision"])
    require("only_device_specific_protocol_dictionary_items_removed",[row for row in before["wcs_dict_item"] if not(row["type_code"]=="protocol_type" and row["item_code"] in legacy)]==after["wcs_dict_item"])
    require("no_device_specific_protocol_type_remains",all(row["protocol_type"] not in legacy for row in after["wcs_protocol"]+after["wcs_protocol_revision"]))
    require("point_columns_unchanged",before["point_columns"]==after["point_columns"])
    require("dashboard_preferences_initially_empty",after["counts"].get("wcs_dashboard_point_selection")==0)
    return checks


def check_application_ownership(before,after):
    """The current installation has one owner per selected profile: every existing value must remain exact."""
    checks=[]
    def require(name,condition,**details): checks.append({"name":name,"passed":bool(condition),**details})
    require("flyway_upgraded_24_to_25",after["versions"]==before["versions"]+["25"])
    for table in ("wcs_application","wcs_task","wcs_task_chain","wcs_protocol_point","wcs_protocol_revision","wcs_app_binding","wcs_dict_type","wcs_dict_item","wcs_device_task_number","wcs_device_task_number_lease"):
        require(table+"_all_existing_values_and_times_preserved",before[table]==after[table],rows=len(before[table]))
    stripped=[{key:value for key,value in row.items() if key not in ("app_id","legacy_template")} for row in after["wcs_protocol"]]
    require("protocols_only_add_owner_and_legacy_classification",before["wcs_protocol"]==stripped)
    protocols={row["id"]:row for row in after["wcs_protocol"]}
    require("each_active_protocol_belongs_to_its_application",all(protocols[a["protocol_id"]]["app_id"]==a["id"] and not protocols[a["protocol_id"]]["legacy_template"] for a in after["wcs_application"] if a["protocol_id"] is not None))
    require("unowned_profiles_are_explicit_legacy_templates",all((p["app_id"] is None)==p["legacy_template"] for p in protocols.values()))
    require("point_schema_unchanged",before["point_columns"]==after["point_columns"])
    require("all_table_counts_preserved_except_flyway",all(after["counts"].get(table)==count+(1 if table=="flyway_schema_history" else 0) for table,count in before["counts"].items()))
    return checks


def check(before,after):
    if before["versions"][-1]=="26" and after["versions"][-1]=="27":
        checks=[{"name":"flyway_upgraded_26_to_27","passed":after["versions"]==before["versions"]+["27"]}]
        for key,value in before.items():
            if key not in ("counts","versions"):
                checks.append({"name":key+"_unchanged","passed":after.get(key)==value})
        checks.append({"name":"all_existing_table_counts_unchanged_except_flyway","passed":all(after["counts"].get(table)==n+(table=="flyway_schema_history") for table,n in before["counts"].items())})
        checks.append({"name":"only_new_table_is_empty_navigation_journal","passed":set(after["counts"])-set(before["counts"])=={"wcs_agv_navigation_command"} and after["counts"]["wcs_agv_navigation_command"]==0})
        return checks
    if before["versions"][-1]=="25" and after["versions"][-1]=="26":
        checks=[{"name":"flyway_upgraded_25_to_26","passed":after["versions"]==before["versions"]+["26"]}]
        for key,value in before.items():
            if key not in ("counts","versions"):
                checks.append({"name":key+"_unchanged","passed":after.get(key)==value})
        checks.append({"name":"all_existing_table_counts_unchanged_except_flyway","passed":all(after["counts"].get(table)==n+(table=="flyway_schema_history") for table,n in before["counts"].items())})
        checks.append({"name":"only_new_table_is_singleton_lifecycle_lock","passed":set(after["counts"])-set(before["counts"])=={"wcs_resource_lifecycle_lock"} and after["counts"]["wcs_resource_lifecycle_lock"]==1})
        return checks
    if before["versions"][-1]=="24" and after["versions"][-1]=="25": return check_application_ownership(before,after)
    if before["versions"][-1]=="23": return check_application_adapters(before,after)
    if before["versions"][-1]=="19":return check_point_field_removal(before,after,"rw","point_rw","19","20")
    if before["versions"][-1]=="20":return check_point_field_removal(before,after,"point_group","point_group","20","21")
    checks=[]
    def require(name,condition,**details):
        checks.append({"name":name,"passed":bool(condition),**details})
    def object_value(value,empty=None):
        if value is None: return {} if empty is None else empty
        return json.loads(value) if isinstance(value,str) else value
    def redact(value):
        if isinstance(value,dict): return {key:redact(item) for key,item in value.items() if not re.search(r"secret|token|password|authorization|app_key",key.lower())}
        if isinstance(value,list): return [redact(item) for item in value]
        return value
    for table in ("wcs_task","wcs_task_chain","wcs_application"):
        old={row["id"]:row for row in before[table]}; new={row["id"]:row for row in after[table]}
        require(table+"_row_ids_preserved",set(old)==set(new),rows=len(old))
        for field in ("create_time","last_update_time"):
            require(table+"_"+field+"_preserved",all(row.get(field)==new[row_id].get(field) for row_id,row in old.items()),rows=len(old))
        if table=="wcs_task":
            old_fields=("task_no","status","task_type","take_row","take_column","take_floor","put_row","put_column","put_floor","port_num","dispatch_time","finish_time","handshake_step")
            require("task_legacy_columns_and_state_preserved",all(all(row.get(key)==new[row_id].get(key) for key in old_fields) for row_id,row in old.items()))
            require("task_original_request_recorded",all(bool(row.get("original_request")) for row in new.values()),rows=len(new))
            require("task_legacy_scope_per_row",all(row.get("idempotency_scope")=="legacy:"+str(row["id"]) for row in new.values()))
            require("nonterminal_tasks_held",all(bool(row.get("held_reason")) for row in new.values() if row["status"] not in ("completed","failed","cancelled")))
            require("unattributed_independent_tasks_held",all(bool(row.get("held_reason")) for row in new.values() if not row.get("chain_id") and row.get("caller_app_id") is None))
            require("all_original_task_columns_except_payload_preserved",all(all(key=="payload" or value==new[row_id].get(key) for key,value in row.items()) for row_id,row in old.items()))
            original_matches=[]
            payload_matches=[]
            for row_id,row in old.items():
                backup=object_value(new[row_id]["original_request"])
                expected={"taskNo":row["task_no"],"appId":row["app_id"],"task_type":row["task_type"]}
                for key in ("take_row","take_column","take_floor","put_row","put_column","put_floor","port_num"):
                    expected[key]=row.get(key) if row.get(key) is not None else 0
                original_matches.append(all(value==backup.get(key) for key,value in expected.items()))
                try: payload_matches.append(backup.get("payload")==redact(object_value(row.get("payload"))))
                except (ValueError,TypeError): payload_matches.append(backup.get("payload_parse_error") is True and new[row_id].get("payload")==row.get("payload"))
            require("task_original_request_metadata_matches_source",all(original_matches),rows=len(original_matches))
            require("task_original_payload_content_matches_source_after_redaction",all(payload_matches),rows=len(payload_matches))
        if table=="wcs_task_chain":
            require("chain_business_state_preserved",all(row.get("status")==new[row_id].get("status") for row_id,row in old.items()))
            require("valid_chain_original_segments_recorded",all(bool(row.get("original_segments")) for row in new.values()))
            require("nonterminal_chains_held",all(bool(row.get("held_reason")) for row in new.values() if row["status"] not in ("completed","failed","cancelled")))
            require("all_original_chain_columns_except_segments_preserved",all(all(key=="segments" or value==new[row_id].get(key) for key,value in row.items()) for row_id,row in old.items()))
            require("chain_original_segments_match_source_after_redaction",all(object_value(new[row_id]["original_segments"])==redact(object_value(row["segments"],[])) for row_id,row in old.items()),rows=len(old))
    require("flyway_upgraded_16_to_20",before["versions"][-1]=="16" and after["versions"][-4:]==["17","18","19","20"])
    protocols={row["id"]:row for row in after["wcs_protocol"]}
    require("no_protocol_auto_publication",all(row["lifecycle_state"]=="draft" and row.get("published_revision_id") is None for row in protocols.values()))
    old_protocols={row["id"]:row for row in before["wcs_protocol"]}
    known=[]
    for row in before["wcs_application"]:
        if row["direction"]=="upstream" and row["device_type"]=="none" and row["is_valid"] and old_protocols[row["protocol_id"]]["protocol_code"]=="STACKER_STD" and (row["app_code"]=="WMS-MAIN" or row["app_code"].startswith("TOKEN-LINK-")):
            known.append(row["id"])
    applications={row["id"]:row for row in after["wcs_application"]}
    require("known_http_apps_reclassified_and_waiting",all(protocols[applications[app_id]["protocol_id"]]["protocol_code"]=="HTTP_DEVICE_CONTRACT" and applications[app_id]["configuration_state"]=="awaiting_contract" and applications[app_id]["protocol_revision_id"] is None for app_id in known),rows=len(known))
    old_points={row["id"]:row for row in before["wcs_protocol_point"]}
    new_points={row["id"]:row for row in after["wcs_protocol_point"]}
    physical_ids={key for key,row in old_protocols.items() if row["protocol_code"] in ("STACKER_STD","STACKER_OPCUA")}
    require("physical_protocol_native_types_and_addresses_not_guessed",all(row["data_type"]==new_points[key]["data_type"] and row["address"]==new_points[key]["address"] for key,row in old_points.items() if row["protocol_id"] in physical_ids))
    require("point_rw_column_removed","rw" not in after["point_columns"])
    require("obsolete_point_rw_dictionary_removed",all(row["type_code"]!="point_rw" for table in ("wcs_dict_type","wcs_dict_item") for row in after[table]))
    source_ids={key for key,row in protocols.items() if row["protocol_code"] in ("PALLET_SIM_OPCUA","CONVEYOR_SIM","LIFT_SIM","DOOR_SIM")}
    http_ids={key for key,row in protocols.items() if row["protocol_code"]=="HTTP_DEVICE_CONTRACT"}
    def identity(point): return (point["tenant_id"],point["template_code"],point["template_version"],point["operation"],point["direction"],point["field_name"],point["logical_type"])
    source_identities={identity(row) for row in new_points.values() if row["protocol_id"] in source_ids and row["is_valid"] and row["point_scope"]=="business"}
    http_identities={identity(row) for row in new_points.values() if row["protocol_id"] in http_ids and row["is_valid"]}
    require("http_request_response_field_sets_match_explicit_sources",source_identities==http_identities,rows=len(http_identities))
    require("no_automatic_protocol_revision_or_lease",after["counts"].get("wcs_protocol_revision")==0 and after["counts"].get("wcs_device_task_number_lease")==0)
    require("device_number_rows_initialized",after["counts"].get("wcs_device_task_number")==before["counts"].get("wcs_application"))
    require("task_chain_audit_coverage",after["counts"].get("wcs_contract_migration_audit",0)>=len(before["wcs_task"])+len(before["wcs_task_chain"]))
    return checks


def main():
    global PRIVATE_ERROR_LOG
    parser=argparse.ArgumentParser()
    parser.add_argument("--source-container",default="cayleywcs-postgres")
    parser.add_argument("--source-version",choices=("16","19","20","23","24","25","26"),default="19")
    parser.add_argument("--target-version",choices=("20","21","24","25","26","27"),default="20")
    parser.add_argument("--task-id",default="TASK-20261013-008BD7B2")
    parser.add_argument("--target-container",default="cayleywcs-migration-rehearsal-pg-20261013")
    parser.add_argument("--port",type=int,default=25433)
    parser.add_argument("--work-dir",required=True)
    parser.add_argument("--backend",required=True)
    parser.add_argument("--java-dir",required=True)
    parser.add_argument("--classpath-file",required=True)
    parser.add_argument("--evidence",required=True)
    parser.add_argument("--resume-restored",action="store_true")
    args=parser.parse_args()
    if (args.source_version,args.target_version) not in (("16","20"),("19","20"),("20","21"),("23","24"),("24","25"),("25","26"),("26","27")):
        parser.error("Supported upgrades are V16 to V20, V19 to V20, V20 to V21, V23 to V24, V24 to V25, V25 to V26, or V26 to V27")
    if args.source_container==args.target_container: raise RuntimeError("Source and target must be distinct")
    root=pathlib.Path(args.work_dir).resolve(); root.mkdir(parents=True,exist_ok=True)
    PRIVATE_ERROR_LOG=root/"operation.private.errors.log"
    backend=pathlib.Path(args.backend).resolve()
    private_dump=root/"wcs-before-migration.dump"
    before=json.loads((root/"before.private.json").read_text(encoding="utf-8")) if args.resume_restored else snapshot(args.source_container)
    if not args.resume_restored: save(root/"before.private.json",before)
    if before["versions"][-1]!=args.source_version: raise RuntimeError("Source version differs from explicit expected version; don't downgrade it")
    if not args.resume_restored:
        command(["docker","exec",args.source_container,"sh","-c",
                 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges -Fc'],stdout_file=private_dump)
    user="rehearsal_owner"; database="wcs_rehearsal"; password=secrets.token_hex(32)
    environment=root/"postgres.private.env"
    if args.resume_restored:
        password=dict(line.split("=",1) for line in environment.read_text().splitlines())["POSTGRES_PASSWORD"]
        purpose=command(["docker","inspect",args.target_container,"--format",'{{index .Config.Labels "com.cayleywcs.purpose"}}']).decode().strip()
        if purpose!="isolated-migration-rehearsal": raise RuntimeError("Refusing to resume unowned target")
    else:
        environment.write_text(f"POSTGRES_USER={user}\nPOSTGRES_DB={database}\nPOSTGRES_PASSWORD={password}\n",encoding="utf-8")
    image=command(["docker","inspect",args.source_container,"--format","{{.Config.Image}}"]).decode().strip()
    if not args.resume_restored:
        command(["docker","run","-d","--name",args.target_container,"--label","com.cayleywcs.purpose=isolated-migration-rehearsal",
                 "--env-file",str(environment),"-p",f"127.0.0.1:{args.port}:5432",image])
    for attempt in range(40):
        # The image's temporary initdb server accepts local sockets, then shuts down.
        # Only the final server listens on TCP; don't mistake the temporary server for ready.
        result=subprocess.run(["docker","exec",args.target_container,"pg_isready","-h","127.0.0.1","-U",user,"-d",database],capture_output=True)
        if result.returncode==0: break
        time.sleep(.25)
    else: raise RuntimeError("Isolated PostgreSQL did not become ready")
    if not args.resume_restored:
        with private_dump.open("rb") as dump:
            result=subprocess.run(["docker","exec","-i",args.target_container,"pg_restore","-U",user,"-d",database,"--no-owner","--no-privileges"],stdin=dump,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if result.returncode: (root/"restore.private.log").write_bytes(result.stderr); raise RuntimeError("Restore failed; inspect private log")
    restored=snapshot(args.target_container); save(root/"restored.private.json",restored)
    if not stable_snapshot_equal(before,restored): raise RuntimeError("Restored business tables differ from source snapshot; source changed during dump or restore mismatch")
    config=root/"flyway.private.properties"
    config.write_text(f"url=jdbc:postgresql://127.0.0.1:{args.port}/{database}?stringtype=unspecified\nuser={user}\npassword={password}\ntarget={args.target_version}\n",encoding="utf-8")
    classes=root/"runner-classes"; classes.mkdir(exist_ok=True)
    separator=";" if __import__('os').name=="nt" else ":"
    dependencies=pathlib.Path(args.classpath_file).read_text().strip()
    # Freeze a built artifact; another agent compiling target/classes must not alter this rehearsal.
    frozen_classes=root/"backend-classes"; frozen_classes.mkdir(exist_ok=True)
    jar=backend/"target/cayleywcs-backend-0.1.0-SNAPSHOT.jar"
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith("BOOT-INF/classes/") and not entry.is_dir():
                destination=(frozen_classes/entry.filename.removeprefix("BOOT-INF/classes/")).resolve()
                if not destination.is_relative_to(frozen_classes.resolve()): raise RuntimeError("Unsafe jar entry")
                destination.parent.mkdir(parents=True,exist_ok=True); destination.write_bytes(archive.read(entry))
    classpath=str(frozen_classes)+separator+dependencies
    source=pathlib.Path(__file__).with_name("SchemaMigrationRunner.java")
    java_directory=pathlib.Path(args.java_dir)
    if (java_directory/"bin").is_dir(): java_directory=java_directory/"bin"
    command([str(java_directory/"javac.exe"),"-cp",classpath,"-d",str(classes),str(source)])
    complete_cp=str(classes)+separator+classpath
    java=str(java_directory/"java.exe")
    command([java,"-cp",complete_cp,"SchemaMigrationRunner",str(config)],stdout_file=root/"migration.private.log")
    after=snapshot(args.target_container); save(root/"after.private.json",after)
    checks=check(before,after)
    command([java,"-cp",complete_cp,"SchemaMigrationRunner",str(config)],stdout_file=root/"repeat.private.log")
    repeated=snapshot(args.target_container)
    checks.append({"name":"repeat_migration_does_not_change_rows_or_times","passed":after==repeated})
    production_after=snapshot(args.source_container)
    checks.append({"name":"source_business_tables_unchanged_by_rehearsal","passed":stable_snapshot_equal(before,production_after)})
    evidence={"task_id":args.task_id,"scope":f"Read-only current PG backup; isolated PG actual Flyway upgrade to V{args.target_version}, no Spring device workers",
              "source_version":before["versions"][-1],"target_version":after["versions"][-1],"postgres_image":image,
              "dump_sha256":hashlib.sha256(private_dump.read_bytes()).hexdigest(),"dump_bytes":private_dump.stat().st_size,
              "backend_jar_sha256":hashlib.sha256((backend/"target/cayleywcs-backend-0.1.0-SNAPSHOT.jar").read_bytes()).hexdigest(),
              "counts_before":before["counts"],"counts_after":after["counts"],"checks":checks,
              "summary":{"checks":len(checks),"passed":sum(item["passed"] for item in checks),"failed":sum(not item["passed"] for item in checks)},
              "private_data":"Dump, original business rows and generated isolated credentials remain outside repository; evidence excludes all request payloads and secrets"}
    save(args.evidence,evidence)
    print(json.dumps(evidence["summary"]))
    if evidence["summary"]["failed"]: raise RuntimeError("Migration rehearsal checks failed; inspect whitelisted evidence")


if __name__=="__main__":
    main()
