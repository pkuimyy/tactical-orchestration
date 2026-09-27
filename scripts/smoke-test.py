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
        expected = json.loads((root / 'scenarios/m5-recon-expected.json').read_text())
        assert results[0]['manifest']['rulesVersion'] == expected['rulesVersion']
        assert results[0]['manifest']['randomVersion'] == expected['randomVersion']
        assert results[0]['result']['stateHash'] == expected['stateHash']
        assert len(results[0]['result']['events']) == expected['eventCount']
        assert hashlib.sha256(logs[0].encode()).hexdigest() == expected['eventsSha256']
        # Empty orders hold units; only N+1 report deliveries are emitted.
        path = f"/api/v1/games/{created[0]['id']}"
        for side in ['BLUE', 'RED']:
            assert request(path + '/orders/' + side, token, '{"day":1,"expectedVersion":0,"orders":[]}', method='PUT')[0] == 200
            assert request(path + '/commit/' + side, token, '{"day":1,"expectedVersion":1}')[0] == 200
        held_events = request(path + '/resolve', token, '{"day":1}')[1]['result']['events']
        assert held_events and all(e['kind'] == 'REPORT_DELIVERED' and e['day'] == 2 for e in held_events)
        assert request(path + '/days/1/events', token, raw=True)[1].endswith('\n')
        # M3: real HTTP combat, known-only retreat, stock use and replayable manifests.
        combat = json.loads((root / 'scenarios/m3-combat-line.json').read_text())
        combat_orders = json.loads((root / 'scenarios/m3-combat-orders.json').read_text())
        def frozen_game(scenario, iterations=4):
            status, draft = request('/api/v1/scenarios/import', token, json.dumps(scenario))
            assert status == 201
            status, revision = request(f"/api/v1/scenarios/{draft['id']}/revisions", token, '{"expectedVersion":1}')
            assert status == 200
            status, game = request('/api/v1/games', token, json.dumps({'revisionId':revision['id'], 'seed':42, 'maxIterations':iterations}))
            assert status == 201
            return f"/api/v1/games/{game['id']}"
        def resolve_orders(path, day, blue, red, blue_operation=None, red_operation=None):
            for side, orders in [('BLUE',blue),('RED',red)]:
                assert request(path+'/orders/'+side,token,json.dumps({'day':day,'expectedVersion':0,'orders':orders,'operation':blue_operation if side=='BLUE' else red_operation}),method='PUT')[0] == 200
                assert request(path+'/commit/'+side,token,json.dumps({'day':day,'expectedVersion':1}))[0] == 200
            status, result = request(path+'/resolve',token,json.dumps({'day':day}))
            assert status == 200, result
            assert request(path+'/resolve',token,json.dumps({'day':day})) == (200,result)
            return result
        path = frozen_game(combat)
        invalid = {'day':1,'expectedVersion':0,'orders':[{'orderId':'bad','regimentId':'assault','route':[],'action':'BOMBARD','target':{'q':3,'r':1}}]}
        assert request(path+'/orders/BLUE',token,json.dumps(invalid),method='PUT')[0] == 400
        invalid['orders'][0] = {'orderId':'bad','regimentId':'assault','route':[],'doctrine':{'schemaVersion':1,'template':'BREAKTHROUGH','withdrawBelowPercent':101,'supplyId':''}}
        assert request(path+'/orders/BLUE',token,json.dumps(invalid),method='PUT')[0] == 400
        combat_result = resolve_orders(path,1,combat_orders['blue'],combat_orders['red'])
        assert combat_result == resolve_orders(frozen_game(combat),1,combat_orders['blue'],combat_orders['red'])
        decisions = [e for e in combat_result['result']['events'] if e['decision'] and e['regimentId']=='assault']
        assert decisions[0]['decision']['action'] == 'WITHDRAW'
        assert decisions[0]['decision']['targetSupplyId'] == 'rear'
        assert all(k['id'] != 'hidden' for k in decisions[0]['decision']['knowledge'])
        assert next(r for r in combat_result['result']['world']['regiments'] if r['id']=='assault')['position'] == {'q':1,'r':1}
        assert any(e['damage'] for e in combat_result['result']['events'])
        changed = json.loads(json.dumps(combat))
        changed['supplies'][1]['stock'] = 1
        changed['supplies'][1]['position'] = {'q':6,'r':0}
        hidden_result = resolve_orders(frozen_game(changed),1,combat_orders['blue'],combat_orders['red'])
        assert decisions == [e for e in hidden_result['result']['events'] if e['decision'] and e['regimentId']=='assault']
        before_hp = sum(c['hp'] for r in combat_result['result']['world']['regiments'] if r['id']=='assault' for c in r['companies'])
        rest = resolve_orders(path,2,[{'orderId':'rest','regimentId':'assault','route':[],'action':'REST'}],[])
        after_hp = sum(c['hp'] for r in rest['result']['world']['regiments'] if r['id']=='assault' for c in r['companies'])
        stock = next(s['stock'] for s in rest['result']['world']['supplies'] if s['id']=='rear')
        assert after_hp > before_hp and 200-stock == after_hp-before_hp
        assert rest['result']['memory']['assault']['doctrine']['supplyId'] == 'rear'
        status, combat_jsonl = request(path+'/days/1/events',token,raw=True)
        assert status == 200
        assert [json.loads(line) for line in combat_jsonl.splitlines()] == combat_result['result']['events']
        (root / 'tactical-server/target/m3-http-run.json').write_text(json.dumps(combat_result,ensure_ascii=False,indent=2)+'\n')
        (root / 'tactical-server/target/m3-http-events.jsonl').write_text(combat_jsonl)
        expected_combat = json.loads((root / 'scenarios/m5-combat-expected.json').read_text())
        assert combat_result['manifest']['rulesVersion'] == expected_combat['rulesVersion']
        assert combat_result['result']['stateHash'] == expected_combat['stateHash']
        assert len(combat_result['result']['events']) == expected_combat['eventCount']
        assert hashlib.sha256(combat_jsonl.encode()).hexdigest() == expected_combat['eventsSha256']
        assault = next(r for r in combat_result['result']['world']['regiments'] if r['id']=='assault')
        assert assault['position'] == expected_combat['assaultPosition']
        assert all(c['hp']==expected_combat['assaultInfantryHp'] for c in assault['companies'] if c['type']=='INFANTRY')
        print('M3 state hash:',combat_result['result']['stateHash'])
        print('M3 events SHA-256:',hashlib.sha256(combat_jsonl.encode()).hexdigest())
        # M4: coordinated engineering, read-only projections, typed operation manifest and replay.
        river = json.loads((root / 'scenarios/m4-river-operation.json').read_text())
        river_orders = json.loads((root / 'scenarios/m4-river-orders.json').read_text())
        river_path = frozen_game(river)
        before = request(river_path+'/view?perspective=DIVISION&side=BLUE',token)[1]
        assert all(r['side']=='BLUE' for r in before['world']['regiments'])
        bad_plan = json.loads(json.dumps(river_orders['blueOperation']))
        bad_plan['nodes'][0]['after'] = ['cross']
        assert request(river_path+'/orders/BLUE',token,json.dumps({'day':1,'expectedVersion':0,'orders':river_orders['blue'],'operation':bad_plan}),method='PUT')[0]==400
        coordinated = resolve_orders(river_path,1,river_orders['blue'],[],river_orders['blueOperation'])
        assert coordinated == resolve_orders(frozen_game(river),1,river_orders['blue'],[],river_orders['blueOperation'])
        assert next(r['position'] for r in coordinated['result']['world']['regiments'] if r['id']=='blue-armor')=={'q':4,'r':0}
        cross = next(n for n in coordinated['result']['operations'] if n['orderId']=='cross')
        assert cross['status']=='COMPLETED' and cross['confirmed']=={'bridge':'SUCCESS'}
        assert coordinated['result']['world']['edges'][0]['bridge']=='INTACT'
        for perspective in ['DIVISION','OMNISCIENT','DIVISION']:
            status, view = request(river_path+'/view?perspective='+perspective+'&side=BLUE',token)
            assert status==200
            if perspective=='DIVISION':
                assert all(r['side']=='BLUE' for r in view['world']['regiments'])
                assert view['knowledge']['contacts']['blue-armor']['observedDay']==1
            assert request(river_path+'/days/1',token)[1]==coordinated
        river_jsonl=request(river_path+'/days/1/events',token,raw=True)[1]
        (root/'tactical-server/target/m4-http-run.json').write_text(json.dumps(coordinated,ensure_ascii=False,indent=2)+'\n')
        (root/'tactical-server/target/m4-http-events.jsonl').write_text(river_jsonl)
        golden=json.loads((root/'scenarios/m5-river-expected.json').read_text())
        assert coordinated['result']['stateHash']==golden['stateHash']
        assert hashlib.sha256(river_jsonl.encode()).hexdigest()==golden['eventsSha256']
        assert len(coordinated['result']['events'])==golden['eventCount']
        print('M4: DAG validation, confirmed bridge crossing, N+1 delivery, projection isolation and replay passed')
        # M5: saved first-day blueprint, authored knowledge, point-in-time replay and markers.
        lab=json.loads((root/'scenarios/m5-doctrine-lab.json').read_text())
        bad_lab=json.loads(json.dumps(lab)); bad_lab['initialKnowledge'][0]['regimentIds']=['missing']
        assert request('/api/v1/scenarios/import',token,json.dumps(bad_lab))[0]==400
        lab_path=frozen_game(lab,6)
        planning=request(lab_path+'/turn',token)[1]
        assert planning['blue']['operation']['nodes'] and len(planning['blue']['orders'])==3
        assert not planning['blue']['submitted']
        for side in ['BLUE','RED']:
            batch=planning[side.lower()]
            assert request(lab_path+'/orders/'+side,token,json.dumps({'day':1,'expectedVersion':0,'orders':batch['orders'],'operation':batch['operation']}),method='PUT')[0]==200
        assert request(lab_path+'/blueprint',token,'{"day":1,"blueVersion":0,"redVersion":1}')[0]==409
        status,blueprint=request(lab_path+'/blueprint',token,'{"day":1,"blueVersion":1,"redVersion":1}')
        assert status==201
        assert blueprint['scenario']['setup']['blue']==planning['blue']['orders']
        assert blueprint['scenario']['initialKnowledge']==lab['initialKnowledge']
        markers=request('/api/v1/scenarios/'+blueprint['id']+'/markers',token)[1]
        assert markers['red-line']['bottleneckEquipment']=='FOOT'
        for side in ['BLUE','RED']:
            assert request(lab_path+'/commit/'+side,token,'{"day":1,"expectedVersion":1}')[0]==200
        status,lab_result=request(lab_path+'/resolve',token,'{"day":1}')
        assert status==200
        reopened=frozen_game(blueprint['scenario'],6)
        assert lab_result==resolve_orders(reopened,1,planning['blue']['orders'],planning['red']['orders'],planning['blue']['operation'],planning['red']['operation'])
        assert request(lab_path+'/blueprint',token,'{"day":1,"blueVersion":1,"redVersion":1}')[0]==409
        first=request(lab_path+'/days/1/replay?perspective=OMNISCIENT&side=BLUE',token)[1]
        assert first['index']==0 and first['phase']=='PLANNING' and first['view']['events']==[]
        assert first['view']['world']['edges'][0]['bridge']=='DESTROYED'
        assert 3<first['count']<=124
        final=request(lab_path+f"/days/1/replay?perspective=OMNISCIENT&side=BLUE&frame={first['count']-1}",token)[1]
        assert final['view']['world']==lab_result['result']['world']
        assert final['view']['events']==lab_result['result']['events']
        division=request(lab_path+'/days/1/replay?perspective=DIVISION&side=BLUE',token)[1]
        for frame in range(division['count']):
            shown=request(lab_path+f'/days/1/replay?perspective=DIVISION&side=BLUE&frame={frame}',token)[1]
            if shown['phase']!='REPORTS_AVAILABLE':
                line=shown['view']['knowledge']['contacts']['red-line']['unit']
                assert all(c['hp']==c['maxHp'] for c in line['companies'])
            assert shown['operations']==[]
        assert request(lab_path+'/days/1/replay?frame=9999',token)[0]==400
        assert request(lab_path+'/days/2/replay',token)[0]==404
        assert request(lab_path+'/days/1',token)[1]==lab_result
        (root/'tactical-server/target/m5-lab-run.json').write_text(json.dumps(lab_result,ensure_ascii=False,indent=2)+'\n')
        (root/'tactical-server/target/m5-replay-final.json').write_text(json.dumps(final,ensure_ascii=False,indent=2)+'\n')
        print('M5: blueprint reopen, authored intelligence, server markers, historical projection and immutable replay passed')
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
        print(f'PASS: executable JAR, curl 200/401/400, generated OpenAPI, M1 freeze/two independent games, M2 submit/commit/resolve/replay and invalid lifecycle rejection, M3 combat/knowledge-only retreat/rest, M4 coordination/projection and golden hashes, M5 blueprint/initial knowledge/replay, loopback binding, {tested} external interface refusal(s), no token in logs')
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
