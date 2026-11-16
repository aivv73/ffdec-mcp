#!/usr/bin/env python3
"""Read-only stdio MCP checks on caller-supplied SWF/GFx movies. Outputs stay private."""
import argparse
import hashlib
import json
from pathlib import Path
import selectors
import subprocess
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Client:
    def __init__(self, command, output):
        self.stderr = (output / 'server.stderr.log').open('w')
        self.transcript = (output / 'mcp.jsonl').open('w')
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.stderr, text=True, bufsize=1)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.request_id = 0
        self.warnings = []

    def request(self, method, params=None):
        self.request_id += 1
        request = {'jsonrpc': '2.0', 'id': self.request_id, 'method': method}
        if params is not None:
            request['params'] = params
        self.transcript.write(json.dumps({'request': request}) + '\n')
        self.process.stdin.write(json.dumps(request) + '\n')
        self.process.stdin.flush()
        deadline = time.monotonic() + 60
        while self.selector.select(max(0, deadline - time.monotonic())):
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError('MCP exited before replying')
            response = json.loads(line)
            self.transcript.write(json.dumps({'response': response}) + '\n')
            self.transcript.flush()
            if response.get('id') != self.request_id:
                continue
            if 'error' in response:
                raise RuntimeError(response['error'])
            if 'result' not in response and method == 'shutdown':
                self.warnings.append('Upstream shutdown response omits its null result')
                return None
            return response['result']
        raise TimeoutError(method)

    def tool(self, name, **arguments):
        result = self.request('tools/call', {'name': name, 'arguments': arguments})
        if result.get('isError'):
            raise RuntimeError(f'{name}: {result}')
        return result.get('structuredContent', result)

    def close(self):
        try:
            self.request('shutdown')
            self.process.stdin.close()
            self.process.wait(timeout=10)
        finally:
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=5)
            self.selector.close()
            self.stderr.close()
            self.transcript.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    build = parser.add_mutually_exclusive_group(required=True)
    build.add_argument('--classpath')
    build.add_argument('--jar')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--reference', type=Path, help='Optional direct-JPEXS summary.json for exact source comparison')
    parser.add_argument('movies', nargs='+', type=Path)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    references = {} if args.reference is None else {r['file']: r for r in json.loads(args.reference.read_text())}
    command = [args.java, '-Djava.awt.headless=true', '-Duser.home=' + str(args.output / 'java-home')]
    command += ['-jar', args.jar] if args.jar else ['-cp', args.classpath, 'com.ffdecmcp.FFDecMcpServer']
    report = {'command': command, 'files': []}
    client = Client(command, args.output)
    try:
        report['server'] = client.request('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'as2-movie-verifier', 'version': '1'}})
        tools = client.request('tools/list')['tools']
        required = {'list_as2_scripts', 'decompile_as2_script', 'get_as2_bytecode', 'search_as2_source'}
        assert required <= {t['name'] for t in tools}
        report['tools'] = tools
        for index, input_path in enumerate(args.movies):
            path = input_path.resolve(strict=True)
            before = digest(path)
            row = {'file': path.name, 'sha256_before': before}
            client.tool('open_swf', file_path=str(path))
            try:
                client.tool('get_swf_info', file_path=str(path))
                client.tool('list_tags', file_path=str(path))
                scripts = []
                offset = 0
                while True:
                    page = client.tool('list_as2_scripts', file_path=str(path), offset=offset, limit=200)
                    assert 'next_offset' in page
                    scripts.extend(page['scripts'])
                    if page['next_offset'] is None:
                        break
                    assert page['next_offset'] > offset
                    offset = page['next_offset']
                assert len(scripts) == page['total'] and scripts
                assert len({s['script_path'] for s in scripts}) == len(scripts)
                selected = max(scripts, key=lambda s: s['action_bytes'])['script_path']
                expected = references.get(path.name)
                if expected:
                    assert len(scripts) == expected['as2_sources']
                    selected = expected['decompiled_samples'][0]['script_path']
                row['as2_sources'] = len(scripts)
                row['selected_source'] = selected
                for tool, label, suffix in [('decompile_as2_script', 'source', 'as'), ('get_as2_bytecode', 'pcode', 'pcode')]:
                    pieces = []
                    offset = 0
                    pages = 0
                    while True:
                        page = client.tool(tool, file_path=str(path), script_path=selected, offset=offset, max_chars=4096)
                        assert 'next_offset' in page
                        assert len(page['source'].encode('utf-16-le')) // 2 <= 4096
                        pieces.append(page['source'])
                        pages += 1
                        if page['next_offset'] is None:
                            break
                        assert page['next_offset'] > offset
                        offset = page['next_offset']
                    text = ''.join(pieces)
                    assert text and 'function' in text.lower()
                    alternate = []
                    offset = 0
                    while True:
                        page = client.tool(tool, file_path=str(path), script_path=selected, offset=offset, max_chars=65536)
                        alternate.append(page['source'])
                        if page['next_offset'] is None:
                            break
                        offset = page['next_offset']
                    assert text == ''.join(alternate), 'Text changed between page sizes'
                    row[label + '_paging_verified'] = True
                    destination = args.output / f'{index}.{suffix}'
                    destination.write_text(text)
                    row[label + '_sha256'] = digest(destination)
                    row[label + '_pages'] = pages
                    if label == 'source' and expected:
                        assert row[label + '_sha256'] == expected['decompiled_samples'][0]['sha256']
                offset = 0
                while True:
                    search = client.tool('search_as2_source', file_path=str(path), query='function', offset=offset, scan_limit=20, limit=1)
                    assert not search['failures'], search['failures']
                    if search['matches']:
                        row['search_match'] = search['matches'][0]['script_path']
                        break
                    assert search['next_offset'] is not None, 'No AS2 function found'
                    offset = search['next_offset']
            finally:
                client.tool('close_swf', file_path=str(path))
                row['input_unchanged'] = digest(path) == before
                assert row['input_unchanged']
                report['files'].append(row)
                (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(row), flush=True)
    finally:
        client.close()
    report['server_exit_code'] = client.process.returncode
    report['protocol_warnings'] = client.warnings
    assert client.process.returncode == 0
    (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
