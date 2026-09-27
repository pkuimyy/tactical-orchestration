#!/usr/bin/env python3
"""Linux packaged-server smoke test; needs Java 25, Python 3 and curl."""
import json
import hashlib
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[1]
jar = root / 'tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar'
with tempfile.TemporaryDirectory(prefix='tactical-smoke-') as work:
    work = Path(work)
    log_path = work / 'server.log'
    with log_path.open('w') as log:
        server = subprocess.Popen(['java', '-jar', str(jar), '--server.port=0'],
                                  cwd=work, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            content = log_path.read_text()
            match = re.search(r'Tomcat started on port (\d+)', content)
            if match:
                port = int(match[1])
                break
            if server.poll() is not None:
                raise RuntimeError('Server exited before readiness; inspect build/runtime configuration')
            time.sleep(0.1)
        else:
            raise RuntimeError('Server startup timed out')
        token = (work / '.runtime/session.token').read_text()

        def request(path, auth=None, data=None, method=None, raw=False):
            args = ['curl', '--silent', '--show-error', '--max-time', '5', '--noproxy', '*',
                    '--write-out', '\n%{http_code}', f'http://127.0.0.1:{port}{path}']
            if method:
                args += ['--request', method]
            if auth:
                args += ['--header', '@-']
            if data:
                args += ['--header', 'Content-Type: application/json', '--data', data]
            result = subprocess.run(args, input=f'Authorization: Bearer {auth}\n' if auth else '',
                                    text=True, capture_output=True, check=True)
            body, status = result.stdout.rsplit('\n', 1)
            return int(status), body if raw else json.loads(body)

        assert request('/health')[0] == 200
        assert request('/api/v1/system')[0] == 401
        assert request('/api/v1/system', 'wrong')[0] == 401
        assert request('/api/v1/system', token)[0] == 200
        assert request('/api/v1/contracts/validate', token,
                       '{"schemaVersion":1,"kind":"COMMAND","id":"smoke-1"}')[0] == 200
        assert request('/api/v1/contracts/validate', token, '{')[0] == 400
        assert request('/api/v1/openapi', token)[1]['openapi'] == '3.1.0'
        # M1 HTTP-only loop: preset -> editable draft -> immutable revision -> two games.
        status, preset = request('/api/v1/presets/river-valley', token)
        assert status == 200
        status, draft = request('/api/v1/scenarios/import', token, json.dumps(preset))
        assert status == 201
        status, revision = request(f"/api/v1/scenarios/{draft['id']}/revisions", token,
                                   json.dumps({'expectedVersion': draft['version']}))
        assert status == 200
        created = []
        for _ in range(2):
            status, game = request('/api/v1/games', token, json.dumps({'revisionId': revision['id'], 'seed': 42}))
            assert status == 201
            created.append(game)
        assert created[0]['id'] != created[1]['id']
        assert created[0]['initialState'] == created[1]['initialState'] == revision['scenario']
        status, events = request(f"/api/v1/scenarios/{draft['id']}/events", token)
        assert status == 200
        (root / 'tactical-server/target/m1-http-events.jsonl').write_text(
            ''.join(json.dumps(event, ensure_ascii=False) + '\n' for event in events))
        # M2 fixed input: pure HTTP submit -> commit -> resolve -> replay.
        pursuit = json.loads((root / 'scenarios/m2-recon-pursuit.json').read_text())
        commands = json.loads((root / 'scenarios/m2-recon-orders.json').read_text())
        status, pd = request('/api/v1/scenarios/import', token, json.dumps(pursuit))
        assert status == 201
        status, pr = request(f"/api/v1/scenarios/{pd['id']}/revisions", token, json.dumps({'expectedVersion': 1}))
        assert status == 200
        results = []
        logs = []
        for _ in range(2):
            status, game = request('/api/v1/games', token, json.dumps({'revisionId': pr['id'], 'seed': commands['seed'], 'maxIterations': commands['maxIterations']}))
            assert status == 201
            path = f"/api/v1/games/{game['id']}"
            assert request(path + '/resolve', token, '{"day":1}')[0] == 409
            assert request(path + '/commit/BLUE', token, '{"day":1,"expectedVersion":1}')[0] == 409
            assert request(path + '/orders/BLUE', token, json.dumps({'day':1,'expectedVersion':0,'orders':commands['red']}), method='PUT')[0] == 400
            assert request(path + '/orders/BLUE', token, '{"day":1,"expectedVersion":0,"orders":[null]}', method='PUT')[0] == 400
            for side in ['BLUE', 'RED']:
                payload = json.dumps({'day': 1, 'expectedVersion': 0, 'orders': commands[side.lower()]})
                submitted = request(path + '/orders/' + side, token, payload, method='PUT')
                assert submitted[0] == 200
                assert request(path + '/orders/' + side, token, payload, method='PUT') == submitted
                commit = request(path + '/commit/' + side, token, '{"day":1,"expectedVersion":1}')
                assert commit[0] == 200
                assert request(path + '/commit/' + side, token, '{"day":1,"expectedVersion":1}') == commit
                assert request(path + '/orders/' + side, token, '{"day":1,"expectedVersion":1,"orders":[]}', method='PUT')[0] == 409
            status, result = request(path + '/resolve', token, '{"day":1}')
            assert status == 200
            assert request(path + '/resolve', token, '{"day":1}') == (200, result)
            assert request(path + '/turn', token)[1]['day'] == 2
            assert request(path + '/resolve', token, '{"day":2}')[0] == 409
            assert request(path + '/commit/BLUE', token, '{"day":1,"expectedVersion":1}')[0] == 409
            assert request(path + '/days/2', token)[0] == 404
            for id, position in commands['expectedPositions'].items():
                assert next(r['position'] for r in result['result']['world']['regiments'] if r['id'] == id) == position
            assert len([e for e in result['result']['events'] if e['kind'] == 'CONTACT']) == commands['expectedContactCount']
            status, jsonl = request(path + '/days/1/events', token, raw=True)
            assert status == 200
            assert [json.loads(line) for line in jsonl.splitlines()] == result['result']['events']
            results.append(result)
            logs.append(jsonl)
        assert results[0] == results[1]
        assert logs[0] == logs[1]
        (root / 'tactical-server/target/m2-http-events.jsonl').write_text(logs[0])
        (root / 'tactical-server/target/m2-http-run.json').write_text(json.dumps(results[0], ensure_ascii=False, indent=2) + '\n')
        print('M2 state hash:', results[0]['result']['stateHash'])
        print('M2 events SHA-256:', hashlib.sha256(logs[0].encode()).hexdigest())
        expected = json.loads((root / 'scenarios/m2-recon-expected.json').read_text())
        assert results[0]['manifest']['rulesVersion'] == expected['rulesVersion']
        assert results[0]['manifest']['randomVersion'] == expected['randomVersion']
        assert results[0]['result']['stateHash'] == expected['stateHash']
        assert len(results[0]['result']['events']) == expected['eventCount']
        assert hashlib.sha256(logs[0].encode()).hexdigest() == expected['eventsSha256']
        # Explicit empty orders hold all units, and export an empty JSONL file.
        path = f"/api/v1/games/{created[0]['id']}"
        for side in ['BLUE', 'RED']:
            assert request(path + '/orders/' + side, token, '{"day":1,"expectedVersion":0,"orders":[]}', method='PUT')[0] == 200
            assert request(path + '/commit/' + side, token, '{"day":1,"expectedVersion":1}')[0] == 200
        assert request(path + '/resolve', token, '{"day":1}')[1]['result']['events'] == []
        assert request(path + '/days/1/events', token, raw=True) == (200, '')
        # Validate actual listening sockets, not merely the configured property.
        listeners = []
        for filename in ['/proc/net/tcp', '/proc/net/tcp6']:
            for row in Path(filename).read_text().splitlines()[1:]:
                fields = row.split()
                address, encoded_port = fields[1].split(':')
                if fields[3] == '0A' and int(encoded_port, 16) == port:
                    listeners.append(address)
        assert listeners and all(a in ('0100007F', '0000000000000000FFFF00000100007F') for a in listeners), listeners
        addresses = subprocess.check_output(['hostname', '-I'], text=True).split()
        tested = 0
        for address in addresses:
            if ':' in address or address.startswith('127.'):
                continue
            with socket.socket() as connection:
                connection.settimeout(1)
                assert connection.connect_ex((address, port)) != 0, 'Non-loopback connection accepted'
                tested += 1
        assert token not in log_path.read_text(), 'Credential leaked into server logs'
        print(f'PASS: executable JAR, curl 200/401/400, generated OpenAPI, M1 freeze/two independent games, M2 submit/commit/resolve/replay and invalid lifecycle rejection, loopback binding, {tested} external interface refusal(s), no token in logs')
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
