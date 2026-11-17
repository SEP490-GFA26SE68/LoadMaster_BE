import ast, os, sys, socket
from pathlib import Path
root=Path.cwd();sys.path.insert(0,str(root))
os.environ['DATABASE_URL']='postgresql+psycopg2://test_only:test_only@127.0.0.1:1/loadmaster_merge_offline'
os.environ['KEYCLOAK_CLIENT_SECRET']='TEST_ONLY_NOT_A_SECRET'
os.environ['APP_ENV']='test'
os.environ.pop('LOADMASTER_PERSISTENCE_TEST_SETTINGS',None)
def blocked(*args,**kwargs):raise RuntimeError('REAL_NETWORK_DISABLED_IN_MERGE_REGRESSION')
original_connect=socket.socket.connect
def safe_connect(sock,address):
 # Windows asyncio uses an internal loopback socketpair, not a service request.
 caller=sys._getframe(1)
 if caller.f_code.co_name=='socketpair' and caller.f_globals.get('__name__')=='socket':
  return original_connect(sock,address)
 return blocked()
socket.socket.connect=safe_connect
socket.socket.connect_ex=blocked
files=list(Path('app').rglob('*.py'));bad=[]
for path in files:
 try:ast.parse(path.read_text(encoding='utf-8-sig'),filename=str(path))
 except SyntaxError as e:bad.append(str(e))
print('AST files:',len(files),'syntax errors:',len(bad))
if bad:print('\n'.join(bad));sys.exit(2)
import pytest
sys.exit(pytest.main(sys.argv[1:]))
