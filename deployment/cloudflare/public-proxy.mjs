const publicOrigin = 'https://scalaris.com.ar';
const publicPath = '/e-medicapp/';
const sourceOrigin = 'https://jereprograma.github.io';
const sourcePath = '/E-MEDICAPP/';
const allowedOrigins = new Set([publicOrigin, sourceOrigin, 'https://localhost']);
const apiMethods = ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'];
const apiHeaders = ['Accept', 'Content-Type', 'X-Auth-Session', 'X-CSRF-TOKEN',
  'Idempotency-Key', 'Range', 'Access-Control-Request-Method', 'Access-Control-Request-Headers'];

function unavailable() {
  return Response.json({ code: 'DEMO_UNAVAILABLE', detail: 'La demostración no está disponible. Volvé a intentar en unos momentos.' },
    { status: 503, headers: { 'Cache-Control': 'no-store' } });
}

function safeBackend(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.pathname !== '/' ||
      url.search || url.hash || !/^[a-z0-9-]+\.trycloudflare\.com$/.test(url.hostname)) {
    throw new Error('Invalid published backend');
  }
  return url;
}

async function proxyApi(request, url) {
  if (!apiMethods.includes(request.method)) {
    return new Response('Method not allowed', { status: 405, headers: { Allow: apiMethods.join(', ') } });
  }
  const origin = request.headers.get('Origin') || publicOrigin;
  if (!allowedOrigins.has(origin)) return new Response('Forbidden origin', { status: 403 });
  try {
    // Read the centrally published tunnel on the server. Browsers only resolve
    // scalaris.com.ar and retain the same API URL after a tunnel restart.
    const marker = new URL(sourcePath + 'deployment.json', sourceOrigin);
    marker.searchParams.set('proxy', Date.now().toString());
    const configuration = await fetch(marker.href, { cf: { cacheTtl: 0 }, redirect: 'manual' });
    if (!configuration.ok) return unavailable();
    const source = safeBackend((await configuration.json()).backendUrl);
    source.pathname = url.pathname.slice(publicPath.length - 1);
    source.search = url.search;
    const headers = new Headers({ Origin: origin });
    for (const name of apiHeaders) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }
    const upstream = await fetch(source.href, {
      method: request.method,
      headers,
      ...(!['GET', 'HEAD'].includes(request.method) ? { body: request.body, duplex: 'half' } : {}),
      redirect: 'manual',
      cf: { cacheTtl: 0 },
    });
    if (upstream.status >= 300 && upstream.status < 400) return unavailable();
    const response = new Response(request.method === 'HEAD' ? null : upstream.body, upstream);
    response.headers.delete('Set-Cookie');
    response.headers.set('Cache-Control', 'no-store');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    return response;
  } catch { return unavailable(); }
}

export async function handleRequest(request) {
  const url = new URL(request.url);
  if (url.origin !== publicOrigin) return new Response('Not found', { status: 404 });
  if (url.pathname === publicPath.slice(0, -1)) {
    url.pathname = publicPath;
    return Response.redirect(url.href, 308);
  }
  if (!url.pathname.startsWith(publicPath)) return new Response('Not found', { status: 404 });
  if (url.pathname.startsWith(publicPath + 'api/')) return proxyApi(request, url);
  if (!['GET', 'HEAD'].includes(request.method)) {
    return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  }
  const source = new URL(sourceOrigin);
  source.pathname = sourcePath + url.pathname.slice(publicPath.length);
  source.search = url.search;
  const fresh = url.pathname === publicPath || /\/(?:index\.html|deployment\.json)$/.test(url.pathname);
  if (fresh) source.searchParams.set('publication', Date.now().toString());
  try {
    const upstream = await fetch(source.href, { method: request.method, redirect: 'manual', cf: { cacheTtl: 0 } });
    if (upstream.status >= 300 && upstream.status < 400 && upstream.headers.has('Location')) {
      const location = new URL(upstream.headers.get('Location'), source);
      if (location.origin !== sourceOrigin || !location.pathname.startsWith(sourcePath)) return unavailable();
      url.pathname = publicPath + location.pathname.slice(sourcePath.length);
      url.search = location.search;
      url.searchParams.delete('publication');
      return Response.redirect(url.href, upstream.status);
    }
    const response = new Response(request.method === 'HEAD' ? null : upstream.body, upstream);
    response.headers.delete('Set-Cookie');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    if (fresh) response.headers.set('Cache-Control', 'no-store');
    return response;
  } catch { return unavailable(); }
}

export default { fetch: handleRequest };
