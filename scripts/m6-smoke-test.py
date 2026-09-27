#!/usr/bin/env python3
"""M6 packaged HTTP acceptance: paired matrix, restart/resume and bounded background load."""
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar'
TARGET = ROOT / 'tactical-server/target'

with tempfile.TemporaryDirectory(prefix='tactical-m6-') as work:
    work = Path(work)
    process = None
    generation = 0
    logs = []

    def start():
        global process, generation, base, token
        generation += 1
        log = work / f'server-{generation}.log'
        logs.append(log)
        with log.open('w') as output:
            process = subprocess.Popen(['java', '-Xmx768m', '-jar', str(JAR), '--server.port=0'], cwd=work, stdout=output, stderr=subprocess.STDOUT)
        for _ in range(600):
            match = re.search(r'Tomcat started on port (\d+)', log.read_text())
            if match:
                base = 'http://127.0.0.1:' + match[1]
                token = (work / '.runtime/session.token').read_text()
                return
            if process.poll() is not None:
                raise AssertionError('Server startup failed: ' + log.read_text()[-2500:])
            time.sleep(.1)
        raise AssertionError('Startup timeout')

    def stop(hard=False):
        if process and process.poll() is None:
            process.kill() if hard else process.terminate()
            process.wait(timeout=30)

    def http(path, method='GET', body=None, expected=200, raw=False, auth=True):
        headers = {'Content-Type': 'application/json'}
        if auth: headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        data = response.read()
        assert response.status == expected, (path, response.status, data[:1000])
        return data if raw else (json.loads(data) if data else None)

    def freeze(scenario):
        draft = http('/api/v1/scenarios/import', 'POST', scenario, 201)
        return http('/api/v1/scenarios/' + draft['id'] + '/revisions', 'POST', {'expectedVersion': 1})

    def create(revision, days, seeds, name, other=None):
        request = {'requestId': name, 'revisionId': revision['id'], 'seeds': seeds, 'maxIterations': 6,
                   'a': {'name': 'A', 'days': days}, 'b': {'name': 'B', 'days': other or days}}
        return http('/api/v1/experiments', 'POST', request, 202), request

    def wait(id):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = http('/api/v1/experiments/' + id)
            if result['status'] not in ['RUNNING', 'QUEUED']:
                assert result['status'] == 'COMPLETED', result
                return http('/api/v1/experiments/' + id + '/export')
            time.sleep(.05)
        raise AssertionError('experiment timed out')

    try:
        start()
        http('/api/v1/experiments', auth=False, expected=401)
        http('/api/v1/scenarios/import', 'POST', {'path': '../../etc/passwd'}, 400)
        http('/api/v1/experiments', 'POST', {}, 400)
        golden = json.loads((ROOT / 'scenarios/m6-regression-expected.json').read_text())['cases']
        fixtures = json.loads((ROOT / 'scenarios/m6-regression-suite.json').read_text())
        for fixture in fixtures:
            revision = freeze(fixture['scenario'])
            body = {'requestId': fixture['id'], 'revisionId': revision['id'], 'seeds': fixture['seeds'], 'maxIterations': fixture['maxIterations'],
                    'a': {'name': 'A', 'days': fixture['days']}, 'b': {'name': 'B', 'days': fixture['days']}}
            job = http('/api/v1/experiments', 'POST', body, 202)
            archive = wait(job['id'])
            for i, seed in enumerate(fixture['seeds']):
                a, b = archive['runs'][i*2:i*2+2]
                assert a['status'] == b['status'] == 'COMPLETED'
                assert a['days'] == b['days'] and a['metrics'] == b['metrics']
                expected = golden[f"{fixture['id']}-{seed}"]
                assert a['days'][-1]['result']['stateHash'] == expected['stateHash']
                events = http(f"/api/v1/experiments/{job['id']}/runs/{i*2}/events", raw=True)
                assert hashlib.sha256(events).hexdigest() == expected['eventsSha256']
            (TARGET / f"m6-http-{fixture['id']}.json").write_text(json.dumps(archive, ensure_ascii=False))
        print('PASS: seven fixed scenario groups / 16 inputs, paired HTTP exact events and state, golden digests', flush=True)

        lab = http('/api/v1/presets/doctrine-lab')
        revision = freeze(lab)
        independent = copy.deepcopy(lab['setup'])
        independent['blueOperation']['mode'] = 'INDEPENDENT'
        job, request = create(revision, [lab['setup']], [0, 1, 2, 3, 42], 'lab-paired', [independent])
        same = http('/api/v1/experiments', 'POST', request, 202)
        assert same['id'] == job['id']
        changed = copy.deepcopy(request); changed['seeds'] = [99]
        http('/api/v1/experiments', 'POST', changed, 409)
        archive = wait(job['id'])
        repeated = dict(request, requestId='lab-repeated')
        second = http('/api/v1/experiments', 'POST', repeated, 202)
        assert wait(second['id'])['runs'] == archive['runs']
        rows = http('/api/v1/experiments/' + job['id'])['rows']
        assert all(r['dependencyDisorders'] == 0 for r in rows if r['variant'] == 'A')
        assert any(r['dependencyDisorders'] > 0 for r in rows if r['variant'] == 'B')
        assert any(rows[i]['blueDamage'] != rows[i+1]['blueDamage'] for i in range(0,len(rows),2))
        game = http(f"/api/v1/experiments/{job['id']}/runs/0/game", 'POST')
        assert http(f"/api/v1/experiments/{job['id']}/runs/0/game", 'POST')['id'] == game['id']
        replay = http(f"/api/v1/games/{game['id']}/days/1/replay?frame=1&perspective=DIVISION")
        http(f"/api/v1/games/{game['id']}/orders/BLUE", 'PUT', {'day':2,'expectedVersion':0,'orders':[]})
        pending = http(f"/api/v1/games/{game['id']}/commit/BLUE", 'POST', {'day':2,'expectedVersion':1})
        bad = copy.deepcopy(request); bad.update(requestId='invalid-seeds', seeds=[1,1])
        http('/api/v1/experiments','POST',bad,400)
        bad = copy.deepcopy(request); bad['requestId']='invalid-dag'; bad['a']['days'][0]['blueOperation']['nodes'][0]['after']=[bad['a']['days'][0]['blueOperation']['nodes'][0]['orderId']]
        http('/api/v1/experiments','POST',bad,400)
        bad = copy.deepcopy(request); bad['requestId']='cross-brigade'; bad['a']['days'][0]['blueOperation']['brigadeId']='red-hq'
        http('/api/v1/experiments','POST',bad,400)
        bad = copy.deepcopy(request); bad['requestId']='oversize-dag'
        bad['a']['days'][0]['blueOperation']['nodes'] *= 17
        http('/api/v1/experiments','POST',bad,400)
        http('/api/v1/scenarios','POST',{'name':'oversize','width':17,'height':17},400)
        http('/api/v1/experiments','POST',{'padding':'x'*65536},413)
        bad = dict(request, requestId='../../etc/passwd')
        http('/api/v1/experiments','POST',bad,400)
        print('PASS: full batch repeated identically, idempotency, coordination/wait/damage differences, invalid seeds/DAG/ownership rejected', flush=True)

        # 24 regiments, 30 days, 2 seeds => 120 simulated days. Pending work survives a hard stop.
        scale = json.loads((ROOT/'scenarios/m6-scale-benchmark.json').read_text())
        scale_revision = freeze(scale)
        days = [{'blue':[], 'red':[], 'blueOperation':None, 'redOperation':None} for _ in range(30)]
        benchmark, benchmark_request = create(scale_revision, days, [1,2], 'scale-restart')
        old_token = token
        stop(hard=True)
        began = time.monotonic(); start()
        assert token != old_token
        assert http(f"/api/v1/games/{game['id']}/turn") == pending
        assert http(f"/api/v1/games/{game['id']}/days/1/replay?frame=1&perspective=DIVISION") == replay
        assert http('/api/v1/experiments/' + job['id'] + '/export') == archive
        resumed = wait(benchmark['id'])
        assert all(len(r['days']) == 30 and r['status']=='COMPLETED' for r in resumed['runs'])
        elapsed = time.monotonic() - began
        print(f'PASS: hard restart, token rotation, pending commands, historical replay, archives and queued 24-regiment / 30-day run restored ({elapsed:.2f}s incl JVM startup)', flush=True)
        # Background benchmark repeated while health/status stay responsive; exact per-run results.
        again, _ = create(scale_revision, days, [1,2], 'scale-repeat')
        latencies=[]
        while True:
            began = time.monotonic(); http('/health', auth=False); latencies.append(time.monotonic()-began)
            state=http('/api/v1/experiments/'+again['id'])
            if state['status']=='COMPLETED': break
            assert state['status'] in ['QUEUED','RUNNING'], state
            time.sleep(.02)
        assert wait(again['id'])['runs'] == resumed['runs']
        assert max(latencies)<2, latencies
        (TARGET/'m6-benchmark.json').write_text(json.dumps({'regiments':24,'days':30,'seeds':[1,2],'pairedRuns':4,'healthSamples':len(latencies),'maxHealthLatencySeconds':max(latencies),'restartAndResumeSeconds':elapsed},indent=2))
        (TARGET/'m6-paired-results.json').write_text(json.dumps(archive,ensure_ascii=False))
        print(f'PASS: background HTTP responsive; max health latency {max(latencies):.3f}s / {len(latencies)} samples; 120-day benchmark repeat identical', flush=True)
        # A pending cancellation remains cancelled across process restart.
        cancel, _=create(scale_revision,days,[3,4],'scale-cancel')
        cancelled=http('/api/v1/experiments/'+cancel['id']+'/cancel','POST')
        assert cancelled['status']=='CANCELLED'
        stop(); start()
        assert http('/api/v1/experiments/'+cancel['id'])['status']=='CANCELLED'
        http('/api/v1/experiments/'+cancel['id'],'DELETE',expected=204)
        http('/api/v1/experiments/'+cancel['id'],expected=404)
        stop(); start()
        http('/api/v1/experiments/'+cancel['id'],expected=404)
        assert all(token not in log.read_text() and old_token not in log.read_text() for log in logs)
        print('PASS: cancelled job stays stopped after restart; no session credentials in logs', flush=True)
    finally:
        stop()
