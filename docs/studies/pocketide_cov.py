"""PocketIDE: which functions of one file each test of a pytest run executes.
Usage: python3 pocketide_cov.py TARGET OUT.json [pytest args...]
OUT.json: {"tests": {"passed": n, "failed": n}, "functions": [{"line", "name", "failed", "passed", "calls"}]}"""
import json, os, sys, threading
target = os.path.realpath(sys.argv[1]); out = sys.argv[2]; args = sys.argv[3:]
names = {}; current = [None]; by_test = {}; calls = {}; outcome = {}
def prof(frame, event, arg):
    if event != 'call': return
    co = frame.f_code
    fn = names.get(co.co_filename)
    if fn is None:
        try: fn = os.path.realpath(co.co_filename)
        except Exception: fn = co.co_filename
        names[co.co_filename] = fn
    if fn != target: return
    key = (co.co_firstlineno, getattr(co, 'co_qualname', co.co_name))
    calls[key] = calls.get(key, 0) + 1
    t = current[0]
    if t is not None: by_test.setdefault(t, set()).add(key)
class Plugin:
    def pytest_runtest_logstart(self, nodeid, location): current[0] = nodeid
    def pytest_runtest_logfinish(self, nodeid, location): current[0] = None
    def pytest_runtest_logreport(self, report):
        if report.failed: outcome[report.nodeid] = 'failed'
        elif report.when == 'call' and report.passed: outcome.setdefault(report.nodeid, 'passed')
code = 1
try:
    import pytest
    threading.setprofile(prof); sys.setprofile(prof)
    code = pytest.main(args, plugins=[Plugin()])
finally:
    sys.setprofile(None)
    fns = {}
    for t, keys in by_test.items():
        o = outcome.get(t)
        if o is None: continue
        for k in keys:
            e = fns.setdefault(k, {'line': k[0], 'name': k[1], 'failed': 0, 'passed': 0, 'calls': calls.get(k, 0)})
            e[o] += 1
    with open(out, 'w') as f:
        json.dump({'tests': {'passed': sum(1 for v in outcome.values() if v == 'passed'), 'failed': sum(1 for v in outcome.values() if v == 'failed')},
                   'functions': sorted(fns.values(), key=lambda e: e['line'])}, f)
sys.exit(int(code))
