"""Opt-in PostgreSQL17 rehearsal. No external DB URL is accepted.

Creates its own Docker container. Uses schema metadata + synthetic rows, never real ledger/customer data.
Run: python test_phase2c_migration.py --run-private-postgres -v
"""
from pathlib import Path
import json
import re
import subprocess
import sys
import time
import unittest
import uuid
import psycopg2
from psycopg2 import sql

HERE=Path(__file__).resolve().parent


class MigrationTest(unittest.TestCase):
    @classmethod
    def docker(cls,*args):
        return subprocess.check_output(['docker',*args],text=True,timeout=30).strip()

    @classmethod
    def setUpClass(cls):
        if '--run-private-postgres' not in ORIGINAL_ARGS:
            raise unittest.SkipTest('Explicit --run-private-postgres is required; no external DB is used')
        cls.name='loadmaster-phase2c-test-'+uuid.uuid4().hex
        cls.password=uuid.uuid4().hex
        cls.docker('run','-d','--rm','--name',cls.name,'-e','POSTGRES_DB=phase2c_test',
                   '-e','POSTGRES_PASSWORD='+cls.password,'-p','127.0.0.1::5432','postgres:17')
        cls.addClassCleanup(cls.docker,'stop',cls.name)
        port=json.loads(cls.docker('inspect',cls.name))[0]['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
        cls.kwargs=dict(host='127.0.0.1',port=port,dbname='phase2c_test',user='postgres',password=cls.password,connect_timeout=3)
        for _ in range(60):
            try:
                db=psycopg2.connect(**cls.kwargs);db.close();return
            except psycopg2.OperationalError:time.sleep(0.25)
        raise RuntimeError('Private PostgreSQL did not start')

    def setUp(self):
        self.db=psycopg2.connect(**self.kwargs);self.db.autocommit=True
        self.addCleanup(self.db.close)
        # This guard cannot be bypassed by an environment variable or command-line DB URL.
        assert self.kwargs['dbname']=='phase2c_test' and self.kwargs['host']=='127.0.0.1'
        self.run_sql('DROP SCHEMA public CASCADE; CREATE SCHEMA public;')
        self.run_sql('CREATE TABLE companies(id bigint PRIMARY KEY); CREATE TABLE trips(id bigint PRIMARY KEY); INSERT INTO companies VALUES(1); INSERT INTO trips VALUES(7);')
        metadata=json.loads((HERE/'schema-fixture.json').read_text(encoding='utf-8'))
        for table in sorted({c['table_name'] for c in metadata['columns']}):
            columns=[]
            for col in metadata['columns']:
                if col['table_name']!=table:continue
                typ=col['data_type']
                if col['character_maximum_length']:typ+='('+str(col['character_maximum_length'])+')'
                columns.append(sql.SQL('{} {} {}').format(sql.Identifier(col['column_name']),sql.SQL(typ),
                    sql.SQL('NOT NULL' if col['is_nullable']=='NO' else '')))
            with self.db.cursor() as cur:
                cur.execute(sql.SQL('CREATE TABLE public.{} ({})').format(sql.Identifier(table),sql.SQL(',').join(columns)))
        for constraint in metadata['constraints']:
            with self.db.cursor() as cur:
                cur.execute(sql.SQL('ALTER TABLE public.{} ADD CONSTRAINT {} {}').format(
                    sql.Identifier(constraint['table_name']),sql.Identifier(constraint['conname']),sql.SQL(constraint['definition'])))
        for index in metadata['indexes']:
            if not self.query('SELECT to_regclass(%s)',('public.'+index['index_name'],))[0][0]:
                self.run_sql(index['definition'])
        self.run_sql("""
          INSERT INTO subscription_plans(id,plan_code,name,tier,billing_cycle,algorithm_tier,monthly_credits,active)
          VALUES(1,'BASIC_MONTHLY','Synthetic BASIC','BASIC','MONTHLY','EP_DBLF',3000,true),
                (2,'PRO_MONTHLY','Synthetic PRO','PRO','MONTHLY','EP_DBLF_GA',10000,true),
                (3,'ULTIMATE_MONTHLY','Synthetic ULTIMATE','ULTIMATE','MONTHLY','EP_DBLF_GA_AI',30000,true);
          INSERT INTO credit_accounts VALUES(1,10,0,1);
          CREATE TABLE company_subscriptions(id bigint PRIMARY KEY,plan_id bigint,status text,expires_at timestamp);
          INSERT INTO company_subscriptions VALUES(1,1,'ACTIVE','2030-01-01');
        """)
        for i in range(1,25):
            self.run_sql("INSERT INTO optimization_jobs(id,trip_id,job_uuid,algorithm_name,status) VALUES(%s,7,%s,'EP_DBLF',%s)",
                         (i,str(uuid.UUID(int=i)),'RUNNING' if i<=6 else 'COMPLETED'))

    def query(self,statement,params=None):
        with self.db.cursor() as cur:cur.execute(statement,params);return cur.fetchall()

    def run_sql(self,statement,params=None):
        try:
            with self.db.cursor() as cur:cur.execute(statement,params)
        except Exception:
            with self.db.cursor() as cur:cur.execute('ROLLBACK')
            raise

    def script(self,name):self.run_sql((HERE/name).read_text(encoding='utf-8'))

    def data(self,include_catalog=True):
        tables=['optimization_jobs','credit_accounts','credit_transactions','company_subscriptions']
        if include_catalog:tables.append('subscription_plans')
        return {t:self.query('SELECT * FROM public.'+t+' ORDER BY id') for t in tables}

    def constraints(self):return self.query("SELECT conrelid::regclass::text,conname,pg_get_constraintdef(oid),convalidated FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1,2")
    def indexes(self):return self.query("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY 1,2")
    def schema(self):return self.constraints(),self.indexes(),self.query("SELECT table_name,column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='public' ORDER BY 1,ordinal_position")
    def usage(self,reference,kind='USAGE',tx_id=1):
        self.run_sql("INSERT INTO credit_transactions(id,credit_account_id,amount,type,reference,refunded,created_at) VALUES(%s,1,-2,%s,%s,false,'2026-11-05 00:00:00')",(tx_id,kind,reference))

    def test_01_minimal_schema_migration_preserves_all_rows_and_unrelated_constraints(self):
        before=self.data();constraints=[c for c in self.constraints() if c[1]!='optimization_jobs_algorithm_name_check']
        self.script('02-credit-idempotency.sql');self.script('03-algorithm-check.sql');self.script('05-postflight.sql')
        self.assertEqual(before,self.data())
        self.assertEqual(constraints,[c for c in self.constraints() if c[1]!='optimization_jobs_algorithm_name_check'])
        self.assertNotIn('uk_phase2_job_uuid',[i[1] for i in self.indexes()])
        self.assertIn('uk_phase2_credit_usage_reference',[i[1] for i in self.indexes()])

    def test_02_repeated_migrations_are_noop(self):
        for name in ['02-credit-idempotency.sql','03-algorithm-check.sql','04-pro-catalog.sql']:self.script(name)
        before=self.schema(),self.data()
        for name in ['02-credit-idempotency.sql','03-algorithm-check.sql','04-pro-catalog.sql','05-postflight.sql']:self.script(name)
        self.assertEqual(before,(self.schema(),self.data()))

    def test_03_all_existing_algorithm_names_null_and_new_names_allowed_unknown_rejected(self):
        self.run_sql("UPDATE optimization_jobs SET algorithm_name='GENETIC_ALGORITHM' WHERE id=24; UPDATE optimization_jobs SET algorithm_name=NULL WHERE id=23")
        before=self.data()
        self.script('03-algorithm-check.sql')
        self.assertEqual(before,self.data())
        for value in ['EP_DBLF','GENETIC_ALGORITHM','EP_DBLF_GA','EP_DBLF_GA_AI',None]:
            self.run_sql('UPDATE optimization_jobs SET algorithm_name=%s WHERE id=24',(value,))
        with self.assertRaises(psycopg2.errors.CheckViolation):
            self.run_sql("UPDATE optimization_jobs SET algorithm_name='UNKNOWN' WHERE id=24")

    def test_04_equivalent_usage_index_different_name_reused(self):
        self.run_sql("CREATE UNIQUE INDEX another_usage_index ON credit_transactions(reference) WHERE type='USAGE'")
        before=self.indexes();self.script('02-credit-idempotency.sql');self.assertEqual(before,self.indexes())

    def test_05_wrong_named_usage_index_aborts_without_schema_changes(self):
        self.run_sql('CREATE INDEX uk_phase2_credit_usage_reference ON credit_transactions(reference)')
        before=self.schema(),self.data()
        with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')
        self.assertEqual(before,(self.schema(),self.data()))

    def test_06_existing_uuid_unique_index_other_name_reused(self):
        self.run_sql('ALTER TABLE optimization_jobs RENAME CONSTRAINT uk2t1ly949hnjlhly02pe9cku30 TO alternate_uuid_unique')
        self.script('02-credit-idempotency.sql')
        self.assertNotIn('uk_phase2_job_uuid',[i[1] for i in self.indexes()])

    def test_07_truly_missing_uuid_unique_index_is_created(self):
        self.run_sql('ALTER TABLE optimization_jobs DROP CONSTRAINT uk2t1ly949hnjlhly02pe9cku30')
        self.script('02-credit-idempotency.sql')
        self.assertIn('uk_phase2_job_uuid',[i[1] for i in self.indexes()])

    def test_08_duplicate_uuid_aborts_without_data_repair(self):
        self.run_sql('ALTER TABLE optimization_jobs DROP CONSTRAINT uk2t1ly949hnjlhly02pe9cku30; UPDATE optimization_jobs SET job_uuid=\'duplicate\'')
        before=self.schema(),self.data()
        with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')
        self.assertEqual(before,(self.schema(),self.data()))

    def test_09_null_and_blank_uuid_abort(self):
        for invalid in [None,'  ']:
            self.run_sql('UPDATE optimization_jobs SET job_uuid=%s WHERE id=24',(invalid,))
            with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')

    def test_10_duplicate_usage_aborts_without_ledger_deletion(self):
        self.usage(str(uuid.UUID(int=1)));self.usage(str(uuid.UUID(int=1)),tx_id=2)
        before=self.schema(),self.data()
        with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')
        self.assertEqual(before,(self.schema(),self.data()))

    def test_11_null_and_blank_usage_reference_abort(self):
        for i,invalid in enumerate([None,'  '],1):
            self.usage(invalid,tx_id=i)
            with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')

    def test_12_usage_only_unique_refund_with_same_reference_is_not_blocked(self):
        self.script('02-credit-idempotency.sql');ref=str(uuid.UUID(int=1));self.usage(ref)
        with self.assertRaises(psycopg2.errors.UniqueViolation):self.usage(ref,tx_id=2)
        self.usage(ref,'REFUND',3);self.usage(ref,'REFUND',4)
        self.assertEqual(3,self.query('SELECT count(*) FROM credit_transactions')[0][0])

    def test_13_incompatible_column_aborts_not_silently_cast(self):
        self.run_sql('ALTER TABLE optimization_jobs ALTER COLUMN parent_plan_id TYPE text')
        before=self.schema()
        with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')
        self.assertEqual(before,self.schema())

    def test_14_missing_column_aborts_not_silently_added(self):
        self.run_sql('ALTER TABLE optimization_jobs DROP COLUMN company_id')
        before=self.schema()
        with self.assertRaises(psycopg2.Error):self.script('02-credit-idempotency.sql')
        self.assertEqual(before,self.schema())

    def test_15_unknown_check_aborts_and_preserves_custom_business_constraint(self):
        self.run_sql("ALTER TABLE optimization_jobs DROP CONSTRAINT optimization_jobs_algorithm_name_check; ALTER TABLE optimization_jobs ADD CONSTRAINT optimization_jobs_algorithm_name_check CHECK (algorithm_name='EP_DBLF')")
        before=self.schema()
        with self.assertRaises(psycopg2.Error):self.script('03-algorithm-check.sql')
        self.assertEqual(before,self.schema())

    def test_16_catalog_changes_only_monthly_credits_and_rerun_noop(self):
        before=self.data(False);schema=self.schema()
        plans=self.query('SELECT id,plan_code,name,tier,billing_cycle,algorithm_tier,active FROM subscription_plans ORDER BY id')
        self.script('04-pro-catalog.sql');self.script('04-pro-catalog.sql')
        self.assertEqual(before,self.data(False));self.assertEqual(schema,self.schema())
        self.assertEqual(plans,self.query('SELECT id,plan_code,name,tier,billing_cycle,algorithm_tier,active FROM subscription_plans ORDER BY id'))
        self.assertEqual([(3000,),(15000,),(30000,)],self.query('SELECT monthly_credits FROM subscription_plans ORDER BY id'))

    def test_17_unexpected_catalog_values_abort_without_updates(self):
        for assignment in ["monthly_credits=12000","monthly_credits=NULL","tier='BASIC'", "billing_cycle='YEARLY'", "algorithm_tier='EP_DBLF'",'active=false']:
            self.run_sql("UPDATE subscription_plans SET monthly_credits=10000,tier='PRO',billing_cycle='MONTHLY',algorithm_tier='EP_DBLF_GA',active=true WHERE id=2")
            self.run_sql('UPDATE subscription_plans SET '+assignment+' WHERE id=2')
            before=self.data()
            with self.assertRaises(psycopg2.Error):self.script('04-pro-catalog.sql')
            self.assertEqual(before,self.data())

    def test_18_missing_pro_not_inserted_or_subscription_activated(self):
        self.run_sql('DELETE FROM subscription_plans WHERE id=2');before=self.data()
        with self.assertRaises(psycopg2.Error):self.script('04-pro-catalog.sql')
        self.assertEqual(before,self.data())

    def test_19_preflight_and_postflight_read_only_and_do_not_mutate_data(self):
        before=self.schema(),self.data();self.script('01-preflight.sql');self.assertEqual(before,(self.schema(),self.data()))
        self.script('02-credit-idempotency.sql');self.script('03-algorithm-check.sql')
        before=self.schema(),self.data();self.script('05-postflight.sql');self.assertEqual(before,(self.schema(),self.data()))

    def test_20_postflight_rejects_unmigrated_schema(self):
        with self.assertRaises(psycopg2.Error):self.script('05-postflight.sql')

    def test_21_old_proposal_guard_calibrated_to_clone_still_duplicates_index_and_cannot_rerun(self):
        old=json.loads((HERE/'schema-fixture.json').read_text(encoding='utf-8'))['original_proposal_sql']
        # Reparsed metadata distributes array casts differently on this PostgreSQL image.
        # Adjust ONLY the guard's expected spelling for this demonstration; never edit the real old SQL.
        definition=self.query("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='optimization_jobs_algorithm_name_check'")[0][0]
        old=re.sub(r'\$expected\$.*?\$expected\$',lambda _: '$expected$'+definition+'$expected$',old,count=1,flags=re.S)
        self.run_sql(old)
        self.assertIn('uk_phase2_job_uuid',[i[1] for i in self.indexes()])
        with self.assertRaises(psycopg2.Error):self.run_sql(old)

    def test_23_original_old_proposal_rejects_reparsed_equivalent_check_without_partial_ddl(self):
        old=json.loads((HERE/'schema-fixture.json').read_text(encoding='utf-8'))['original_proposal_sql']
        before=self.schema(),self.data()
        with self.assertRaises(psycopg2.Error):self.run_sql(old)
        self.assertEqual(before,(self.schema(),self.data()))

    def test_24_postflight_catalog_reports_pending_update_wrong_identity_and_missing_row(self):
        query=(HERE/'05-postflight.sql').read_text(encoding='utf-8').split('-- catalog-verification:\n',1)[1].split(';',1)[0]
        self.assertEqual([True,False,True],[row[-1] for row in self.query(query)])
        self.script('04-pro-catalog.sql')
        self.assertTrue(all(row[-1] for row in self.query(query)))
        self.run_sql("UPDATE subscription_plans SET tier='BASIC' WHERE id=2")
        self.assertFalse(self.query(query)[1][-1])
        self.run_sql('DELETE FROM subscription_plans WHERE id=2')
        self.assertEqual(3,len(self.query(query)));self.assertFalse(self.query(query)[1][-1])

    def test_25_catalog_trigger_side_effects_are_not_executed(self):
        self.run_sql('''CREATE FUNCTION forbidden_grant() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN UPDATE credit_accounts SET balance=balance+5000; RETURN NEW; END $$;
          CREATE TRIGGER custom_grant AFTER UPDATE ON subscription_plans
          FOR EACH ROW EXECUTE FUNCTION forbidden_grant();''')
        before=self.data()
        with self.assertRaises(psycopg2.Error):self.script('04-pro-catalog.sql')
        self.assertEqual(before,self.data())

    def test_22_writer_lock_timeout_aborts_without_partial_schema_change(self):
        before=self.schema(),self.data()
        locker=psycopg2.connect(**self.kwargs)
        try:
            with locker.cursor() as cur:cur.execute('LOCK TABLE optimization_jobs IN ACCESS EXCLUSIVE MODE')
            with self.assertRaises(psycopg2.errors.LockNotAvailable):self.script('02-credit-idempotency.sql')
        finally:locker.rollback();locker.close()
        self.assertEqual(before,(self.schema(),self.data()))


ORIGINAL_ARGS=sys.argv[:]
if __name__=='__main__':
    sys.argv=[arg for arg in sys.argv if arg!='--run-private-postgres']
    unittest.main()
