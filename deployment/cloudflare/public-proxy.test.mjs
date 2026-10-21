import assert from 'node:assert/strict';
import { test } from 'node:test';
import { handleRequest } from './public-proxy.mjs';

const base = 'https://scalaris.com.ar/e-medicapp/';
const backend = 'https://demo-current.trycloudflare.com';
const marker = () => Response.json({ backendUrl: backend, commit: 'published' });

test('accepts the approved VPS origin and rejects similar or private destinations', async (t) => {
  const approved = 'https://srv2083548.hstgr.cloud';
  const fetcher = t.mock.method(globalThis, 'fetch', async (url) => {
    if (url.includes('deployment.json')) return Response.json({ backendUrl: approved });
    assert.equal(url, approved + '/api/bootstrap');
    return Response.json({ mode: 'DEMO' });
  });
  assert.equal((await handleRequest(new Request(base + 'api/bootstrap'))).status, 200);
  for (const backendUrl of ['https://srv2083548.hstgr.cloud.attacker.example', 'https://srv999.hstgr.cloud', 'https://srv2083548.hstgr.cloud:8443', 'https://127.0.0.1']) {
    fetcher.mock.mockImplementation(async () => Response.json({ backendUrl }));
    assert.equal((await handleRequest(new Request(base + 'api/bootstrap'))).status, 503);
  }
});

test('serves only its own path and canonicalizes the public URL', async () => {
  assert.equal((await handleRequest(new Request('https://scalaris.com.ar/tienda/'))).status, 404);
  const response = await handleRequest(new Request(base.slice(0, -1) + '?source=qa'));
  assert.equal(response.status, 308);
  assert.equal(response.headers.get('Location'), base + '?source=qa');
});

test('static requests never send browser credentials to GitHub and refresh HTML/configuration', async (t) => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url: new URL(url), options });
    return new Response('static', { headers: { 'Set-Cookie': 'not-forwarded=true' } });
  });
  for (const file of ['', 'deployment.json']) {
    const response = await handleRequest(new Request(base + file, { headers: { Cookie: 'private', 'X-Auth-Session': 'private' } }));
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
    assert.equal(response.headers.get('Set-Cookie'), null);
    assert.equal(requests.at(-1).url.origin, 'https://jereprograma.github.io');
    assert.ok(requests.at(-1).url.searchParams.has('publication'));
    assert.equal(requests.at(-1).options.headers, undefined);
  }
});

test('API resolves the tunnel server-side and preserves session, CSRF, uploads and responses', async (t) => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options });
    if (requests.length === 1) return marker();
    return new Response('stored', { headers: { 'X-Auth-Session': 'rotated', 'Set-Cookie': 'never=forward', 'Content-Disposition': 'attachment; filename=test.pdf' } });
  });
  const response = await handleRequest(new Request(base + 'api/documents?type=RX', {
    method: 'POST', body: 'binary-upload', headers: {
      Cookie: 'scalaris-private', Authorization: 'not-for-this-api',
      'X-Auth-Session': 'own-session', 'X-CSRF-TOKEN': 'csrf', 'Idempotency-Key': 'operation',
      'Content-Type': 'multipart/form-data; boundary=example',
    },
  }));
  assert.equal(requests[0].options.headers, undefined);
  assert.equal(requests[1].url, backend + '/api/documents?type=RX');
  const headers = requests[1].options.headers;
  assert.equal(headers.get('Cookie'), null);
  assert.equal(headers.get('Authorization'), null);
  assert.equal(headers.get('Origin'), 'https://scalaris.com.ar');
  assert.equal(headers.get('X-Auth-Session'), 'own-session');
  assert.equal(headers.get('X-CSRF-TOKEN'), 'csrf');
  assert.equal(headers.get('Idempotency-Key'), 'operation');
  assert.equal(await new Response(requests[1].options.body).text(), 'binary-upload');
  assert.equal(response.headers.get('Set-Cookie'), null);
  assert.equal(response.headers.get('X-Auth-Session'), 'rotated');
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(response.headers.get('Content-Disposition'), 'attachment; filename=test.pdf');
});

test('GitHub Pages and Android retain CORS preflight and header sessions', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.includes('deployment.json')) return marker();
    assert.equal(options.method, 'OPTIONS');
    assert.equal(options.headers.get('Origin'), 'https://jereprograma.github.io');
    assert.equal(options.headers.get('Access-Control-Request-Method'), 'POST');
    assert.equal(options.headers.get('Access-Control-Request-Headers'), 'x-auth-session,x-csrf-token');
    return new Response(null, { headers: { 'Access-Control-Allow-Origin': 'https://jereprograma.github.io', 'Access-Control-Allow-Methods': 'POST' } });
  });
  const response = await handleRequest(new Request(base + 'api/auth/login', {
    method: 'OPTIONS', headers: { Origin: 'https://jereprograma.github.io', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'x-auth-session,x-csrf-token' },
  }));
  assert.equal(response.headers.get('Access-Control-Allow-Origin'), 'https://jereprograma.github.io');
});

test('rejects foreign origins, invalid tunnel destinations and upstream redirects', async (t) => {
  const fetcher = t.mock.method(globalThis, 'fetch', async () => Response.json({ backendUrl: 'https://attacker.example' }));
  assert.equal((await handleRequest(new Request(base + 'api/bootstrap', { headers: { Origin: 'https://attacker.example' } }))).status, 403);
  assert.equal(fetcher.mock.callCount(), 0);
  for (const backendUrl of ['http://127.0.0.1', 'https://attacker.example', 'https://user:pass@demo.trycloudflare.com', 'https://demo.trycloudflare.com/api']) {
    fetcher.mock.mockImplementation(async () => Response.json({ backendUrl }));
    assert.equal((await handleRequest(new Request(base + 'api/bootstrap'))).status, 503);
  }
  fetcher.mock.mockImplementation(async (url) => url.includes('deployment.json') ? marker() : Response.redirect('https://attacker.example'));
  assert.equal((await handleRequest(new Request(base + 'api/bootstrap'))).status, 503);
});

test('returns a controlled, uncached failure if the tunnel is offline', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url.includes('deployment.json')) return marker();
    throw new Error('DNS unavailable');
  });
  const response = await handleRequest(new Request(base + 'api/bootstrap'));
  assert.equal(response.status, 503);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal((await response.json()).code, 'DEMO_UNAVAILABLE');
});
