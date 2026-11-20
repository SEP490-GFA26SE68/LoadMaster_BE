"""Read-only independent field-level comparison against the stopped rehearsal clone."""
from pathlib import Path
import json,subprocess,time
import psycopg2
from dotenv import dotenv_values
from sqlalchemy.engine import make_url
W=Path('D:/VNR_game/phase2d1');result=json.loads((W/'full-clone-result.json').read_text())
private=Path(result['private_artifact_directory']);access=json.loads((private/'clone-access.json').read_text())
name=result['target']['container'];info=json.loads(subprocess.check_output(['docker','inspect',name],text=True))[0]
assert info['Config']['Labels']['loadmaster.phase2d1']==name.removeprefix('loadmaster-phase2d1-')
assert access['database']==result['target']['database'] and access['database'].startswith('loadmaster_clone_2d1_')
def digest(db):
 with db.cursor() as cur:
  cur.execute("SELECT plan_code,md5((to_jsonb(p)-'monthly_credits')::text),monthly_credits FROM public.subscription_plans p ORDER BY plan_code")
  return {row[0]:(row[1],row[2]) for row in cur.fetchall()}
source=make_url(dotenv_values(Path('D:/chuyennganh/KI9/Capstone_LoadMaster_BE/OptimizeService/.env'))['DATABASE_URL'].removeprefix('jdbc:'))
try:
 subprocess.run(['docker','start',name],check=True,stdout=subprocess.DEVNULL)
 for _ in range(50):
  info=json.loads(subprocess.check_output(['docker','inspect',name],text=True))[0]
  bindings=info['NetworkSettings']['Ports'].get('5432/tcp') or []
  if bindings:break
  time.sleep(.1)
 port=int(bindings[0]['HostPort'])
 assert bindings[0]['HostIp']=='127.0.0.1'
 for _ in range(50):
  try:
   clone=psycopg2.connect(host='127.0.0.1',port=port,dbname=access['database'],user='postgres',password=access['admin_password'],connect_timeout=3,
    options='-c default_transaction_read_only=on');break
  except psycopg2.OperationalError:time.sleep(.2)
 clone.set_session(readonly=True)
 with psycopg2.connect(host=source.host,port=source.port or 5432,dbname=source.database,user=source.username,password=source.password,connect_timeout=8,
    options='-c default_transaction_read_only=on') as src:
  src.set_session(readonly=True)
  with clone.cursor() as cur:
   cur.execute('SELECT current_database(),(SELECT system_identifier::text FROM pg_control_system())');clone_id=cur.fetchone()
  with src.cursor() as cur:
   cur.execute('SELECT current_database(),(SELECT system_identifier::text FROM pg_control_system())');source_id=cur.fetchone()
  assert clone_id[0]==access['database'] and source_id[0]==result['source']['database'] and clone_id[1]!=source_id[1]
  a,b=digest(src),digest(clone)
  assert a.keys()==b.keys() and all(a[key][0]==b[key][0] for key in a)
  assert a['PRO_MONTHLY'][1]==10000 and b['PRO_MONTHLY'][1]==15000
  assert all(a[key][1]==b[key][1] for key in a if key!='PRO_MONTHLY')
 clone.close()
 proof={'read_only_both_connections':True,'catalog_rows_compared':len(a),'all_non_monthly_fields_unchanged':True,
   'only_pro_monthly_changed':True,'source_pro':10000,'clone_pro':15000,'clone_stopped_after_check':True}
 (W/'catalog-field-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
 print('PASS: compared every catalog field; only PRO_MONTHLY.monthly_credits differs.')
finally:
 subprocess.run(['docker','stop',name],check=True,stdout=subprocess.DEVNULL)
