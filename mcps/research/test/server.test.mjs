import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('real stdio handshake, queries, validation, and backend error isolation', async () => {
  const requests = [];
  const backend = http.createServer((req, res) => {
    requests.push({ method: req.method, url: req.url });
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/api/papers/missing') { res.end(JSON.stringify({ error: 'not found' })); return; }
    if (req.url === '/api/papers/broken') { res.statusCode = 500; res.end('private diagnostic'); return; }
    if (req.url === '/api/papers/redirect') { res.statusCode = 302; res.setHeader('Location', '/secret'); res.end(); return; }
    res.end(JSON.stringify([{ id: 'demo' }]));
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  const transport = new StdioClientTransport({ command: process.execPath,
    args: [fileURLToPath(new URL('../src/server.mjs', import.meta.url))],
    env: { ...process.env, EKINA_BASE_URL: `http://127.0.0.1:${backend.address().port}`, EKINA_USER_ID: 'fixture-user' } });
  const client = new Client({ name: 'ekina-test', version: '1.0.0' });
  try {
    await client.connect(transport);
    const { tools } = await client.listTools();
    assert.equal(tools.length, 5);
    assert.ok(tools.every(t => t.annotations.readOnlyHint));
    for (const name of ['ekina_list_projects', 'ekina_list_reviews', 'ekina_list_capabilities']) {
      const result = await client.callTool({ name, arguments: {} });
      assert.deepEqual(JSON.parse(result.content[0].text), [{ id: 'demo' }]);
    }
    await client.callTool({ name: 'ekina_list_papers', arguments: { limit: 3 } });
    assert.ok(requests.some(r => r.url === '/api/papers?userId=fixture-user&limit=3'));
    const invalid = await client.callTool({ name: 'ekina_get_paper', arguments: { id: '../secret' } });
    assert.ok(invalid.isError);
    for (const id of ['missing', 'broken', 'redirect']) {
      const result = await client.callTool({ name: 'ekina_get_paper', arguments: { id } });
      assert.ok(result.isError);
      assert.ok(!JSON.stringify(result).includes('private diagnostic'));
    }
    assert.ok(requests.every(r => r.method === 'GET' && !r.url.includes('/secret')));
  } finally {
    await client.close();
    await new Promise(resolve => backend.close(resolve));
  }
});
