"""Full logical clone rehearsal. Source is read-only; targets are NEW labeled Docker resources only.
Never accepts an external target URL. Never drops/truncates/overwrites a database.
The source archive and clone credentials remain in an ACL-restricted private directory.
"""
from pathlib import Path
import csv,datetime,hashlib,io,json,os,re,subprocess,time,uuid,zipfile
import psycopg2
from psycopg2 import sql
from dotenv import dotenv_values
from sqlalchemy.engine import make_url

WORK=Path('D:/VNR_game/phase2d1')
BE=Path('D:/chuyennganh/KI9/Capstone_LoadMaster_BE')
SQLDIR=BE/'LoadMasterService/docs/sql/phase2c'
JAVA=Path('C:/Program Files/Java/jdk-21/bin')
JAVA_SOURCE=Path(__file__).with_name('CloneValidationApplication.java')
token=uuid.uuid4().hex[:12]
PRIVATE=WORK/('private_'+token)
result={'source_read_only':True,'shared_migration_applied':False,'started_at':str(datetime.datetime.now(datetime.timezone.utc))}
container=None

def save():
 (WORK/'full-clone-result.json').write_text(json.dumps(result,indent=2,default=str),encoding='utf-8')

def command(args,label,env=None,stdin=None,stdout=None,timeout=120):
 p=subprocess.run(args,env=env,stdin=stdin,stdout=stdout or subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if p.returncode:
  (PRIVATE/(re.sub('[^a-z0-9]+','_',label.lower())+'.stderr')).write_bytes(p.stderr)
  raise RuntimeError(label+' failed (exit '+str(p.returncode)+'); private diagnostics retained')
 return p.stdout

def docker(*args):return command(['docker',*args],'docker '+args[0]).decode().strip()

def rows(db,query,params=None):
 with db.cursor() as cur:cur.execute(query,params);return cur.fetchall()

def execute(db,query,params=None):
 try:
  with db.cursor() as cur:cur.execute(query,params)
 except Exception:
  db.rollback();raise

def fingerprint(db):
 tables=rows(db,"SELECT n.nspname,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','m','p') AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 1,2")
 output={}
 for schema,table in tables:
  query=sql.SQL("SELECT count(*),md5(COALESCE(string_agg(h,'' ORDER BY h),'')) FROM (SELECT md5(row_to_json(t)::text) h FROM {}.{} t) q").format(sql.Identifier(schema),sql.Identifier(table))
  output[schema+'.'+table]=rows(db,query)[0]
 return output

def inventory(db):
 return {'relations':rows(db,"SELECT n.nspname,c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 1,2,3"),
 'constraints':rows(db,"SELECT n.nspname,c.relname,k.conname,k.contype,k.convalidated FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 1,2,3"),
 'functions':rows(db,"SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,3")}

def preflight(db):
 output={}
 text=(SQLDIR/'01-preflight.sql').read_text(encoding='utf-8')
 with db.cursor() as cur:
  for section in text.split('-- section: ')[1:]:
   name,query=section.split('\n',1);cur.execute(query.split(';',1)[0]);names=[c[0] for c in cur.description]
   output[name.strip()]=[dict(zip(names,row)) for row in cur.fetchall()]
 return output

def run_script(db,name):execute(db,(SQLDIR/name).read_text(encoding='utf-8'))

def validate_boot(label):
 env={k:v for k,v in os.environ.items() if not k.startswith('SPRING_') and k not in ['DATABASE_URL','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS']}
 args=[str(JAVA/'java.exe'),'-cp',CLASSPATH,'rehearsal.CloneValidationApplication',str(PRIVATE/'clone-validation.properties')]
 process=subprocess.run(args,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
 log=process.stdout.decode('utf-8',errors='replace');(PRIVATE/(label+'.log')).write_text(log,encoding='utf-8')
 marker=next((line for line in log.splitlines() if line.startswith('CLONE_VALIDATION_PASS ')),None)
 schema_errors=sorted(set(re.findall(r'Schema-validation:[^\r\n]+',log)))
 # Publish only bounded metadata diagnostics, never exception dumps or row values.
 public={'exit_code':process.returncode,'passed':process.returncode==0 and marker is not None,'summary':marker,'schema_errors':schema_errors}
 result[label]=public;save();print(label+': '+('PASS' if public['passed'] else 'FAIL (see sanitized report)'),flush=True)
 return public

try:
 WORK.mkdir(exist_ok=True);PRIVATE.mkdir(exist_ok=False)
 sid_text=subprocess.check_output(['whoami','/user','/fo','csv','/nh'],text=True)
 sid=next(csv.reader(io.StringIO(sid_text)))[1]
 command(['icacls',str(PRIVATE),'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F','/grant:r','*S-1-5-18:(OI)(CI)F'],'restrict private archive ACL')
 result['private_artifact_directory']=str(PRIVATE)
 env=dotenv_values(BE/'OptimizeService/.env');source=make_url(env['DATABASE_URL'].removeprefix('jdbc:'))
 spring=make_url(dotenv_values(BE/'LoadMasterService/.env')['DATABASE_URL'].removeprefix('jdbc:'))
 assert (source.host,source.port or 5432,source.database)==(spring.host,spring.port or 5432,spring.database)
 result['source']={'host':source.host,'port':source.port or 5432,'database':source.database}
 src=psycopg2.connect(host=source.host,port=source.port or 5432,dbname=source.database,user=source.username,password=source.password,
  connect_timeout=8,options='-c default_transaction_read_only=on -c statement_timeout=120000 -c lock_timeout=5000 -c TimeZone=UTC')
 src.set_session(readonly=True,isolation_level='REPEATABLE READ')
 assert rows(src,'SHOW transaction_read_only')[0][0]=='on'
 source_identity=rows(src,'SELECT current_database(),(SELECT system_identifier::text FROM pg_control_system()),(SELECT oid FROM pg_database WHERE datname=current_database())')[0]
 assert source_identity[0]==source.database
 assert rows(src,'SELECT pg_database_size(current_database())')[0][0]<100*1024*1024,'Stop: backup exceeds reviewed small-dev limit'
 result['source_preflight']=preflight(src)
 assert rows(src,'SELECT count(*) FROM pg_foreign_server')[0][0]==0,'Foreign-server side effects require manual review'
 assert rows(src,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity")[0][0]==0,'RLS functions require manual review'
 assert rows(src,"SELECT count(*) FROM pg_event_trigger WHERE evtenabled<>'D'")[0][0]==0,'DDL event triggers require manual review'
 snapshot=rows(src,'SELECT pg_export_snapshot()')[0][0]
 before_data=fingerprint(src);before_inventory=inventory(src)
 result['source_table_count']=len(before_data)
 helper_env=os.environ.copy()
 helper_env.update(PGHOST='host.docker.internal' if source.host in ['localhost','127.0.0.1','::1'] else source.host,
  PGPORT=str(source.port or 5432),PGDATABASE=source.database,PGUSER=source.username,PGPASSWORD=source.password,
  PGOPTIONS='-c default_transaction_read_only=on -c TimeZone=UTC -c application_name=loadmaster_phase2d1_dump')
 helper=['docker','run','--rm']
 for key in ['PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD','PGOPTIONS']:helper+=['--env',key]
 helper+=['postgres:17']
 identity_sql="SELECT current_database(),(SELECT system_identifier::text FROM pg_control_system()),(SELECT oid FROM pg_database WHERE datname=current_database())"
 identity=command(helper+['psql','-X','-A','-t','-F','|','-c',identity_sql],'verify dump source identity',env=helper_env).decode().strip().split('|')
 assert identity==list(map(str,source_identity)),'Dump helper did not reach the verified source cluster/database'
 archive=PRIVATE/'full-source.dump'
 with archive.open('xb') as out:
  command(helper+['pg_dump','--format=custom','--no-owner','--no-acl','--lock-wait-timeout=5s','--snapshot='+snapshot],
          'full read-only pg_dump',env=helper_env,stdout=out)
 assert archive.stat().st_size>0
 result['archive_bytes']=archive.stat().st_size
 result['archive_sha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
 src.rollback();src.close()
 print('Full read-only archive created; no table filter and no customer rows printed.',flush=True);save()

 network='loadmaster-2d1-isolated-'+token
 # Docker Desktop does not expose dynamic host ports on the internal network here.
 # Use a dedicated bridge, publish ONLY loopback, and load no application external clients.
 docker('network','create','--label','loadmaster.phase2d1='+token,network)
 database='loadmaster_clone_2d1_'+token;container='loadmaster-phase2d1-'+token;password=uuid.uuid4().hex
 assert database!='loadmaster' and database!=source.database and database.startswith('loadmaster_clone_2d1_')
 # The random container name must be unused; Docker refuses an existing name. No volume is mounted/reused.
 command(['docker','run','-d','--name',container,'--label','loadmaster.phase2d1='+token,'--network',network,
          '-e','POSTGRES_DB='+database,'-e','POSTGRES_PASSWORD','-p','127.0.0.1::5432','postgres:17'],
          'create fresh isolated clone',env={**os.environ,'POSTGRES_PASSWORD':password})
 for _ in range(50):
  info=json.loads(docker('inspect',container))[0]
  bindings=info['NetworkSettings']['Ports'].get('5432/tcp') or []
  if bindings:break
  time.sleep(0.1)
 else:raise RuntimeError('Clone port binding could not be verified; restore was not attempted')
 assert info['Config']['Labels']['loadmaster.phase2d1']==token
 port=int(info['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort'])
 assert port!=source.port or source.host not in ['localhost','127.0.0.1']
 target={'host':'127.0.0.1','port':port,'database':database,'container':container,'network':network}
 result['target']=target;save()
 connect=dict(host='127.0.0.1',port=port,dbname=database,user='postgres',password=password,connect_timeout=3,
              options='-c TimeZone=UTC')
 for _ in range(60):
  try:db=psycopg2.connect(**connect);break
  except psycopg2.OperationalError:time.sleep(0.25)
 else:raise RuntimeError('Fresh clone did not become reachable from localhost')
 db.autocommit=True
 target_id=rows(db,'SELECT current_database(),(SELECT system_identifier::text FROM pg_control_system())')[0]
 assert target_id[0]==database and target_id[1]!=source_identity[1]
 assert rows(db,"SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")[0][0]==0,'Refusing restore into a non-empty target'
 result['target_identity_verified']=True;result['target_empty_before_restore']=True;save()
 with archive.open('rb') as inp:
  command(['docker','exec','-i',container,'pg_restore','--username=postgres','--dbname='+database,
    '--no-owner','--no-acl','--exit-on-error'],'restore into fresh clone',stdin=inp)
 clone_before=fingerprint(db);clone_inventory=inventory(db)
 assert before_data==clone_before,'Full row fingerprints differ between source snapshot and restored clone'
 assert before_inventory==clone_inventory,'Full schema object inventory differs after restore'
 result['full_restore_data_matches']=True;result['full_restore_inventory_matches']=True
 result['clone_preflight']=preflight(db)
 result['large_objects']=rows(db,'SELECT count(*) FROM pg_largeobject_metadata')[0][0]
 print('Restored all '+str(len(clone_before))+' tables and matched data/schema-object inventories.',flush=True);save()

 # A clone-only least-privilege role enforces READ ONLY for Boot validation and repository smoke.
 reader_password=uuid.uuid4().hex
 execute(db,sql.SQL('CREATE ROLE phase2d_reader LOGIN PASSWORD {} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT').format(sql.Literal(reader_password)))
 execute(db,sql.SQL('GRANT CONNECT ON DATABASE {} TO phase2d_reader').format(sql.Identifier(database)))
 execute(db,'GRANT USAGE ON SCHEMA public TO phase2d_reader; GRANT SELECT ON ALL TABLES IN SCHEMA public TO phase2d_reader; ALTER ROLE phase2d_reader SET default_transaction_read_only=on')
 (PRIVATE/'clone-access.json').write_text(json.dumps({**target,'admin_password':password,'reader_password':reader_password}),encoding='utf-8')
 (PRIVATE/'clone-validation.properties').write_text(f'clone.jdbc-url=jdbc:postgresql://127.0.0.1:{port}/{database}\nclone.username=phase2d_reader\nclone.password={reader_password}\nspring.flyway.enabled=false\nspring.jpa.hibernate.ddl-auto=validate\nlogging.level.root=WARN\n',encoding='utf-8')
 libs=PRIVATE/'lib';libs.mkdir();classes=PRIVATE/'classes';classes.mkdir()
 jar=BE/'LoadMasterService/target/LoadMasterService-0.0.1-SNAPSHOT.jar'
 with zipfile.ZipFile(jar) as z:
  for item in z.namelist():
   if item.startswith('BOOT-INF/lib/') and item.endswith('.jar'):
    (libs/Path(item).name).write_bytes(z.read(item))
 CLASSPATH=str(classes)+';'+str(BE/'LoadMasterService/target/classes')+';'+str(libs/'*')
 command([str(JAVA/'javac.exe'),'-proc:none','-cp',CLASSPATH,'-d',str(classes),str(JAVA_SOURCE)],'compile safe Boot clone context')
 validate_boot('hibernate_baseline')

 old_check=rows(db,"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public.optimization_jobs'::regclass AND conname='optimization_jobs_algorithm_name_check'")[0][0]
 old_pro=rows(db,"SELECT monthly_credits FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY'")[0][0]
 assert old_pro in [10000,15000]
 result['pro_subscriptions_before']=rows(db,"SELECT count(*),count(*) FILTER(WHERE s.status='ACTIVE') FROM public.company_subscriptions s JOIN public.subscription_plans p ON p.id=s.plan_id WHERE p.plan_code='PRO_MONTHLY'")[0]
 for name in ['01-preflight.sql','02-credit-idempotency.sql','03-algorithm-check.sql','04-pro-catalog.sql','05-postflight.sql']:
  run_script(db,name)
 after=fingerprint(db)
 changes=[table for table in before_data if before_data[table]!=after[table]]
 assert changes==(['public.subscription_plans'] if old_pro==10000 else []),changes
 assert clone_before['public.credit_accounts']==after['public.credit_accounts']
 assert clone_before['public.credit_transactions']==after['public.credit_transactions']
 assert clone_before['public.company_subscriptions']==after['public.company_subscriptions']
 result['migration_passed']=True;result['data_changed_tables']=changes
 result['credit_accounts_unchanged']=True;result['credit_transactions_unchanged']=True;result['subscriptions_unchanged']=True
 result['clone_postflight']=preflight(db)
 result['pro_after']=rows(db,"SELECT monthly_credits FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY'")[0][0]
 validate_boot('hibernate_after_migration')
 assert after==fingerprint(db),'Read-only Boot context changed clone rows'
 # Idempotent reapplication must not change schema-object inventory or data.
 schema_after=inventory(db)
 for name in ['02-credit-idempotency.sql','03-algorithm-check.sql','04-pro-catalog.sql','05-postflight.sql']:run_script(db,name)
 assert after==fingerprint(db) and schema_after==inventory(db)
 result['rerun_noop']=True

 # Clone-only rollback per runbook. New algorithms would block CHECK narrowing; never rewrite jobs.
 assert rows(db,"SELECT count(*) FROM public.optimization_jobs WHERE algorithm_name IS NOT NULL AND algorithm_name NOT IN ('EP_DBLF','GENETIC_ALGORITHM')")[0][0]==0
 execute(db,'BEGIN')
 execute(db,'ALTER TABLE public.optimization_jobs DROP CONSTRAINT optimization_jobs_algorithm_name_check')
 execute(db,sql.SQL('ALTER TABLE public.optimization_jobs ADD CONSTRAINT optimization_jobs_algorithm_name_check {}').format(sql.SQL(old_check)))
 before_names={item[1] for item in before_inventory['relations']}
 if 'uk_phase2_credit_usage_reference' not in before_names:execute(db,'DROP INDEX public.uk_phase2_credit_usage_reference')
 if old_pro==10000:
  execute(db,"UPDATE public.subscription_plans SET monthly_credits=10000 WHERE plan_code='PRO_MONTHLY' AND tier='PRO' AND billing_cycle='MONTHLY' AND algorithm_tier='EP_DBLF_GA' AND monthly_credits=15000")
 execute(db,'COMMIT')
 assert clone_before==fingerprint(db) and clone_inventory==inventory(db)
 result['rollback_full_data_and_inventory_match']=True
 # Verify rollback transaction atomicity by deliberately aborting a clone-only catalog write.
 execute(db,'BEGIN');execute(db,"UPDATE public.subscription_plans SET monthly_credits=monthly_credits+1 WHERE plan_code='PRO_MONTHLY'");execute(db,'ROLLBACK')
 assert clone_before==fingerprint(db)
 result['rollback_before_commit_preserves_data']=True
 # Leave the private clone in the rehearsed target state, not a narrowed rollback state.
 for name in ['02-credit-idempotency.sql','03-algorithm-check.sql','04-pro-catalog.sql','05-postflight.sql']:run_script(db,name)
 assert after==fingerprint(db)
 result['final_clone_state']='migrated; container stopped after rehearsal'
 db.close();result['completed']=True;save()
 print('Migration, idempotent rerun, Credit preservation and clone-only rollback verified.',flush=True)
except Exception as exc:
 result['completed']=False;result['failure_type']=type(exc).__name__
 # This log is ACL-restricted; the public report deliberately omits exception/data details.
 (PRIVATE/'failure.txt').write_text(str(exc),encoding='utf-8')
 save();print('Rehearsal stopped safely: '+type(exc).__name__+'; see sanitized result and private diagnostics.',flush=True)
 raise SystemExit(1) from None
finally:
 if container:
  try:
   docker('stop',container);result['clone_container_stopped']=True
  except Exception:result['clone_container_stopped']=False
 save()
