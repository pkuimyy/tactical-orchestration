#!/usr/bin/env python3
"""Linux packaged-server smoke test; needs Java 25, Python 3 and curl."""
import json
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

        def request(path, auth=None, data=None):
            args = ['curl', '--silent', '--show-error', '--max-time', '5', '--noproxy', '*',
                    '--write-out', '\n%{http_code}', f'http://127.0.0.1:{port}{path}']
            if auth:
                args += ['--header', '@-']
            if data:
                args += ['--header', 'Content-Type: application/json', '--data', data]
            result = subprocess.run(args, input=f'Authorization: Bearer {auth}\n' if auth else '',
                                    text=True, capture_output=True, check=True)
            body, status = result.stdout.rsplit('\n', 1)
            return int(status), json.loads(body)

        assert request('/health')[0] == 200
        assert request('/api/v1/system')[0] == 401
        assert request('/api/v1/system', 'wrong')[0] == 401
        assert request('/api/v1/system', token)[0] == 200
        assert request('/api/v1/contracts/validate', token,
                       '{"schemaVersion":1,"kind":"COMMAND","id":"smoke-1"}')[0] == 200
        assert request('/api/v1/contracts/validate', token, '{')[0] == 400
        assert request('/api/v1/openapi', token)[1]['openapi'] == '3.1.0'
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
        print(f'PASS: executable JAR, curl 200/401/400, OpenAPI, loopback binding, {tested} external interface refusal(s), no token in logs')
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
