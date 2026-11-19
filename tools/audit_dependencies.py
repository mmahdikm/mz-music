"""Query OSV with public resolved Maven coordinates only. Run the graph task first."""
from pathlib import Path
import json
import re
import urllib.request

root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text())[1]
folder = root / 'validation' / version
coordinates = set()
for line in (folder / 'runtime-dependencies.txt').read_text().splitlines():
    match = re.search(r'(?:\+---|\\---) ([\w.-]+):([\w.-]+)(?::([^\s]+))?(?: -> ([^\s]+))?', line)
    if match:
        group, artifact, requested, resolved = match.groups()
        selected = resolved or requested
        if selected and re.match(r'^\d', selected) and ':' not in selected:
            coordinates.add((group + ':' + artifact, selected))
queries = [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
           for name, version in sorted(coordinates)]
assert queries, 'Resolve releaseRuntimeClasspath before scanning'
request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
    data=json.dumps({'queries': queries}).encode(), headers={'Content-Type': 'application/json'})
result = json.load(urllib.request.urlopen(request, timeout=45))
assert len(result['results']) == len(queries)
findings = [{'query': query, 'result': response} for query, response in zip(queries, result['results'])
            if response.get('vulns') or response.get('next_page_token')]
(folder / 'dependency-coordinates.json').write_text(json.dumps(queries, indent=2) + '\n')
(folder / 'osv-response.json').write_text(json.dumps(result, indent=2) + '\n')
summary = {'queried': len(queries), 'findings': findings}
(folder / 'osv-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if findings:
    raise SystemExit('Review advisory matches before distribution; a package match does not establish app exploitability.')
