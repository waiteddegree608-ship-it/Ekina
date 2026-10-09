import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

const base = new URL(process.env.EKINA_BASE_URL || 'http://127.0.0.1:8081');
if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password ||
    base.search || base.hash || base.pathname !== '/') {
  throw new Error('EKINA_BASE_URL must be an HTTP(S) origin without credentials, path, query, or fragment');
}
const userId = process.env.EKINA_USER_ID || 'user-1';
const server = new McpServer({ name: 'ekina-research', version: '0.1.0' });
const annotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true };
const id = z.string().min(1).max(120).regex(/^[a-zA-Z0-9_-]+$/);

async function get(path, query = {}) {
  const url = new URL(path, base);
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, String(value));
  try {
    const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(15000), headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 2 * 1024 * 1024) { await reader.cancel(); throw new Error('Response too large'); }
      chunks.push(value);
    }
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }], ...(data?.error ? { isError: true } : {}) };
  } catch (error) {
    const reason = /^HTTP \d{3}$|^Response too large$/.test(error.message) ? error.message : 'Backend unavailable or invalid JSON';
    return { isError: true, content: [{ type: 'text', text: `Ekina: ${reason}. Check the backend and EKINA_BASE_URL.` }] };
  }
}

server.registerTool('ekina_list_projects', { description: 'List research workspace projects for the configured user.', inputSchema: {}, annotations },
  () => get('/api/projects', { userId }));
server.registerTool('ekina_list_reviews', { description: 'List literature review campaigns and their recorded status.', inputSchema: {}, annotations },
  () => get('/api/reviews', { userId }));
server.registerTool('ekina_list_papers', { description: 'List paper workflow records; status does not establish scientific validity.', inputSchema: { limit: z.number().int().min(1).max(100).default(20) }, annotations },
  ({ limit }) => get('/api/papers', { userId, limit }));
server.registerTool('ekina_get_paper', { description: 'Read one paper workflow by its record ID.', inputSchema: { id }, annotations },
  ({ id }) => get(`/api/papers/${encodeURIComponent(id)}`));
server.registerTool('ekina_list_capabilities', { description: 'List built-in backend capability packs and their tool names.', inputSchema: {}, annotations },
  () => get('/api/capability-packs'));

await server.connect(new StdioServerTransport());
