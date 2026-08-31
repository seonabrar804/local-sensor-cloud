import http from 'node:http';
import https from 'node:https';
import { createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto';
import { appendFile, chmod, mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_DIRECTORY = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');
const JSON_LIMIT = 1024 * 1024;
const FRAME_LIMIT = 5 * 1024 * 1024;
const PAIRING_LIMIT = 64 * 1024;
const PAIRING_TTL_MS = 5 * 60 * 1000;
const HISTORY_LIMIT = 600;
const APPLICATION_ENCRYPTION_HEADER = 'aes-256-gcm-v2';
const APPLICATION_ENCRYPTION_MAGIC = Buffer.from('LSC2');
const APPLICATION_IV_LENGTH = 12;
const APPLICATION_TAG_LENGTH = 16;
const APPLICATION_AAD_PREFIX = 'LocalSensorCloud AES-GCM v2\n';

const CONTENT_TYPES = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml'
};

function safeDeviceId(value) {
  const safe = String(value || '').trim().replace(/[^a-zA-Z0-9_.-]/g, '_').slice(0, 96);
  return safe || 'unknown-device';
}

function safeCamera(value) {
  return ['back', 'front', 'external'].includes(value) ? value : 'back';
}

function jsonResponse(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
    'Content-Type': 'application/json; charset=utf-8'
  });
  response.end(body);
}

function isLoopbackRequest(request) {
  const address = request.socket?.remoteAddress || '';
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}

function pairingCode(certificate, clientNonce, requestId) {
  const digest = createHash('sha256')
    .update(certificate)
    .update(clientNonce)
    .update(requestId, 'utf8')
    .digest();
  return String(digest.readUInt32BE(0) % 1_000_000).padStart(6, '0');
}

async function readBody(request, limit) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > limit) {
      const error = new Error('Request body is too large');
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJsonBody(request, limit) {
  try {
    return JSON.parse((await readBody(request, limit)).toString('utf8'));
  } catch {
    const error = new Error('Request body must be valid JSON');
    error.statusCode = 400;
    throw error;
  }
}

function isoFileTimestamp(date = new Date()) {
  return date.toISOString().replaceAll(':', '-').replaceAll('.', '-');
}

function sendSse(response, event, value) {
  response.write(`event: ${event}\ndata: ${JSON.stringify(value)}\n\n`);
}

export function createSensorCloudServer(options = {}) {
  const dataDirectory = path.resolve(options.dataDirectory || './data');
  const publicDirectory = path.resolve(options.publicDirectory || PUBLIC_DIRECTORY);
  const apkPath = options.apkPath ? path.resolve(options.apkPath) : null;
  const pairingFile = options.pairingFile ? path.resolve(options.pairingFile) : null;
  const serverCertificateDer = options.serverCertificateDer ? Buffer.from(options.serverCertificateDer) : null;
  const telemetryDirectory = path.join(dataDirectory, 'telemetry');
  const frameDirectory = path.join(dataDirectory, 'frames');
  const photoDirectory = path.join(dataDirectory, 'photos');
  const latest = new Map();
  const latestFrames = new Map();
  const histories = new Map();
  const pairedDevices = new Map();
  const pairingRequests = new Map();
  const sseClients = new Set();
  const mjpegClients = new Set();
  let receivedTelemetry = 0;
  let receivedFrames = 0;

  const ready = Promise.all([
    mkdir(telemetryDirectory, { recursive: true }),
    mkdir(frameDirectory, { recursive: true }),
    mkdir(photoDirectory, { recursive: true })
  ]);

  const pairingsReady = (async () => {
    if (!pairingFile) return;
    try {
      const stored = JSON.parse(await readFile(pairingFile, 'utf8'));
      for (const value of Array.isArray(stored.devices) ? stored.devices : []) {
        const deviceId = safeDeviceId(value.deviceId);
        const key = Buffer.from(String(value.applicationKey || ''), 'base64');
        if (key.length !== 32) continue;
        pairedDevices.set(deviceId, {
          applicationKey: key,
          approvedAt: value.approvedAt,
          deviceId,
          deviceName: String(value.deviceName || deviceId).slice(0, 160)
        });
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  })();

  async function persistPairings() {
    if (!pairingFile) return;
    const devices = [...pairedDevices.values()].map(value => ({
      applicationKey: value.applicationKey.toString('base64'),
      approvedAt: value.approvedAt,
      deviceId: value.deviceId,
      deviceName: value.deviceName
    }));
    await mkdir(path.dirname(pairingFile), { recursive: true });
    const temporaryPath = `${pairingFile}.${process.pid}.tmp`;
    await writeFile(temporaryPath, `${JSON.stringify({ devices }, null, 2)}\n`, { mode: 0o600 });
    await rename(temporaryPath, pairingFile);
    await chmod(pairingFile, 0o600).catch(() => {});
  }

  function cleanPairingRequests() {
    const now = Date.now();
    for (const [requestId, value] of pairingRequests) {
      if (value.expiresAt <= now) pairingRequests.delete(requestId);
    }
  }

  function emitSse(event, deviceId, value) {
    for (const client of sseClients) {
      if (!client.deviceId || client.deviceId === deviceId) {
        sendSse(client.response, event, value);
      }
    }
  }

  function emitFrame(deviceId, camera, frame) {
    const header = Buffer.from(`--sensor-frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`);
    const trailer = Buffer.from('\r\n');
    for (const client of mjpegClients) {
      if ((!client.deviceId || client.deviceId === deviceId) && client.camera === camera) {
        client.response.write(header);
        client.response.write(frame);
        client.response.write(trailer);
      }
    }
  }

  function decryptApplicationPayload(request, encryptedBody) {
    const deviceId = safeDeviceId(request.headers['x-sensor-device-id']);
    const pairing = pairedDevices.get(deviceId);
    if (!pairing) {
      const error = new Error('This phone is not approved. Pair it from the laptop dashboard first.');
      error.statusCode = 401;
      throw error;
    }
    if (request.headers['x-sensor-encryption'] !== APPLICATION_ENCRYPTION_HEADER) {
      const error = new Error('Paired AES-GCM application encryption is required');
      error.statusCode = 415;
      throw error;
    }
    const minimumLength = APPLICATION_ENCRYPTION_MAGIC.length + APPLICATION_IV_LENGTH + APPLICATION_TAG_LENGTH;
    if (encryptedBody.length < minimumLength
        || !encryptedBody.subarray(0, APPLICATION_ENCRYPTION_MAGIC.length).equals(APPLICATION_ENCRYPTION_MAGIC)) {
      const error = new Error('Invalid encrypted payload envelope');
      error.statusCode = 400;
      throw error;
    }

    const ivStart = APPLICATION_ENCRYPTION_MAGIC.length;
    const ciphertextStart = ivStart + APPLICATION_IV_LENGTH;
    const tagStart = encryptedBody.length - APPLICATION_TAG_LENGTH;
    const iv = encryptedBody.subarray(ivStart, ciphertextStart);
    const ciphertext = encryptedBody.subarray(ciphertextStart, tagStart);
    const authenticationTag = encryptedBody.subarray(tagStart);
    try {
      const decipher = createDecipheriv('aes-256-gcm', pairing.applicationKey, iv);
      decipher.setAAD(Buffer.from(`${APPLICATION_AAD_PREFIX}${deviceId}\n${request.url || '/'}`, 'utf8'));
      decipher.setAuthTag(authenticationTag);
      return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    } catch {
      const error = new Error('Encrypted payload authentication failed');
      error.statusCode = 400;
      throw error;
    }
  }

  async function handleTelemetry(request, response) {
    await pairingsReady;
    const encryptedBody = await readBody(request, JSON_LIMIT + 64);
    const body = decryptApplicationPayload(request, encryptedBody);
    let payload;
    try {
      payload = JSON.parse(body.toString('utf8'));
    } catch {
      return jsonResponse(response, 400, { error: 'Telemetry must be valid JSON' });
    }
    if (!payload || typeof payload !== 'object' || !payload.deviceId) {
      return jsonResponse(response, 400, { error: 'deviceId is required' });
    }

    const authenticatedDeviceId = safeDeviceId(request.headers['x-sensor-device-id']);
    if (safeDeviceId(payload.deviceId) !== authenticatedDeviceId) {
      return jsonResponse(response, 403, { error: 'Encrypted device identity does not match the upload header' });
    }

    await ready;
    const deviceId = safeDeviceId(payload.deviceId);
    const receivedAt = new Date().toISOString();
    const record = { ...payload, deviceId, receivedAt };
    latest.set(deviceId, record);
    const history = histories.get(deviceId) || [];
    history.push(record);
    if (history.length > HISTORY_LIMIT) history.splice(0, history.length - HISTORY_LIMIT);
    histories.set(deviceId, history);
    receivedTelemetry += 1;

    const dailyLog = path.join(telemetryDirectory, `${receivedAt.slice(0, 10)}.jsonl`);
    await appendFile(dailyLog, `${JSON.stringify(record)}\n`, 'utf8');
    emitSse('telemetry', deviceId, record);
    jsonResponse(response, 202, { accepted: true, receivedAt });
  }

  async function handleFrame(request, response, url) {
    await pairingsReady;
    const encryptedBody = await readBody(request, FRAME_LIMIT + 64);
    const frame = decryptApplicationPayload(request, encryptedBody);
    if (frame.length < 4 || frame[0] !== 0xff || frame[1] !== 0xd8) {
      return jsonResponse(response, 400, { error: 'Body must be a JPEG image' });
    }

    await ready;
    const deviceId = safeDeviceId(url.searchParams.get('deviceId'));
    if (deviceId !== safeDeviceId(request.headers['x-sensor-device-id'])) {
      return jsonResponse(response, 403, { error: 'Encrypted device identity does not match the upload URL' });
    }
    const camera = safeCamera(url.searchParams.get('camera'));
    const frameKey = `${deviceId}:${camera}`;
    const capture = url.searchParams.get('capture') === 'photo' ? 'photo' : 'stream';
    const receivedAt = new Date().toISOString();
    latestFrames.set(frameKey, { frame, receivedAt, deviceId, camera });
    receivedFrames += 1;

    const finalPath = path.join(frameDirectory, `${deviceId}-${camera}.jpg`);
    const temporaryPath = path.join(frameDirectory, `${deviceId}-${camera}.${process.pid}.tmp`);
    await writeFile(temporaryPath, frame);
    await rename(temporaryPath, finalPath);
    let photoFile = null;
    if (capture === 'photo') {
      photoFile = `${deviceId}-${camera}-${isoFileTimestamp()}.jpg`;
      await writeFile(path.join(photoDirectory, photoFile), frame);
    }

    emitFrame(deviceId, camera, frame);
    emitSse('frame', deviceId, { deviceId, camera, receivedAt, bytes: frame.length, capture, photoFile });
    jsonResponse(response, 202, { accepted: true, receivedAt, camera, capture, photoFile });
  }

  async function handlePairingRequest(request, response) {
    if (!serverCertificateDer?.length) {
      return jsonResponse(response, 503, { error: 'Laptop pairing is not configured' });
    }
    const payload = await readJsonBody(request, PAIRING_LIMIT);
    const deviceId = safeDeviceId(payload.deviceId);
    const deviceName = String(payload.deviceName || deviceId).trim().slice(0, 160) || deviceId;
    const clientNonce = Buffer.from(String(payload.clientNonce || ''), 'base64');
    if (clientNonce.length !== 32) {
      return jsonResponse(response, 400, { error: 'clientNonce must contain 32 random bytes' });
    }

    cleanPairingRequests();
    for (const [requestId, value] of pairingRequests) {
      if (value.deviceId === deviceId && value.status === 'pending') pairingRequests.delete(requestId);
    }

    const requestId = randomUUID();
    const createdAt = new Date().toISOString();
    const expiresAt = Date.now() + PAIRING_TTL_MS;
    pairingRequests.set(requestId, {
      clientNonce,
      code: pairingCode(serverCertificateDer, clientNonce, requestId),
      createdAt,
      deviceId,
      deviceName,
      expiresAt,
      remoteAddress: request.socket?.remoteAddress || '',
      status: 'pending'
    });
    jsonResponse(response, 202, {
      expiresAt: new Date(expiresAt).toISOString(),
      requestId,
      status: 'pending'
    });
  }

  async function handlePairingStatus(response, url) {
    cleanPairingRequests();
    const requestId = String(url.searchParams.get('requestId') || '');
    const deviceId = safeDeviceId(url.searchParams.get('deviceId'));
    const clientNonce = Buffer.from(String(url.searchParams.get('clientNonce') || ''), 'base64');
    const value = pairingRequests.get(requestId);
    if (!value || value.deviceId !== deviceId || clientNonce.length !== 32 || !value.clientNonce.equals(clientNonce)) {
      return jsonResponse(response, 404, { error: 'Pairing request was not found or has expired' });
    }
    if (value.status === 'denied') return jsonResponse(response, 200, { status: 'denied' });
    if (value.status !== 'approved') return jsonResponse(response, 200, { status: 'pending' });
    jsonResponse(response, 200, {
      applicationKey: value.applicationKey.toString('base64'),
      approvedAt: value.approvedAt,
      status: 'approved'
    });
  }

  async function listPairingRequests(request, response) {
    if (!isLoopbackRequest(request)) {
      return jsonResponse(response, 403, { error: 'Pairing decisions are available only on the laptop itself' });
    }
    cleanPairingRequests();
    const requests = [...pairingRequests.entries()]
      .filter(([, value]) => value.status === 'pending')
      .map(([requestId, value]) => ({
        code: value.code,
        createdAt: value.createdAt,
        deviceId: value.deviceId,
        deviceName: value.deviceName,
        remoteAddress: value.remoteAddress,
        requestId
      }));
    jsonResponse(response, 200, { requests });
  }

  async function decidePairing(request, response, decision) {
    if (!isLoopbackRequest(request) || request.headers['x-sensor-dashboard'] !== 'local-approval') {
      return jsonResponse(response, 403, { error: 'Approve or deny pairing from the laptop dashboard' });
    }
    const payload = await readJsonBody(request, PAIRING_LIMIT);
    cleanPairingRequests();
    const value = pairingRequests.get(String(payload.requestId || ''));
    if (!value || value.status !== 'pending') {
      return jsonResponse(response, 404, { error: 'Pending pairing request was not found' });
    }
    if (decision === 'deny') {
      value.status = 'denied';
      value.decidedAt = new Date().toISOString();
      return jsonResponse(response, 200, { accepted: false, status: 'denied' });
    }

    await pairingsReady;
    const approvedAt = new Date().toISOString();
    const applicationKey = randomBytes(32);
    pairedDevices.set(value.deviceId, {
      applicationKey,
      approvedAt,
      deviceId: value.deviceId,
      deviceName: value.deviceName
    });
    await persistPairings();
    value.applicationKey = applicationKey;
    value.approvedAt = approvedAt;
    value.status = 'approved';
    jsonResponse(response, 200, {
      accepted: true,
      deviceId: value.deviceId,
      status: 'approved'
    });
  }

  async function serveStatic(response, pathname) {
    const relative = pathname === '/' ? 'index.html' : pathname.slice(1);
    if (!['index.html', 'app.js', 'styles.css'].includes(relative)) {
      return jsonResponse(response, 404, { error: 'Not found' });
    }
    const filePath = path.join(publicDirectory, relative);
    try {
      const content = await readFile(filePath);
      response.writeHead(200, {
        'Cache-Control': 'no-cache',
        'Content-Length': content.length,
        'Content-Type': CONTENT_TYPES[path.extname(filePath)] || 'application/octet-stream'
      });
      response.end(content);
    } catch {
      jsonResponse(response, 404, { error: 'Not found' });
    }
  }

  async function serveApk(response) {
    if (!apkPath) return jsonResponse(response, 404, { error: 'Android APK has not been configured' });
    try {
      const content = await readFile(apkPath);
      response.writeHead(200, {
        'Cache-Control': 'no-store',
        'Content-Disposition': 'attachment; filename="LocalSensorCloud-debug.apk"',
        'Content-Length': content.length,
        'Content-Type': 'application/vnd.android.package-archive',
        'X-Content-Type-Options': 'nosniff'
      });
      response.end(content);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      jsonResponse(response, 404, {
        error: 'Android APK not found. Build it with Gradle before downloading.'
      });
    }
  }

  const requestListener = async (request, response) => {
    if (options.tls) response.setHeader('Strict-Transport-Security', 'max-age=31536000');
    if (request.method === 'OPTIONS') {
      response.writeHead(204, {
        Allow: 'GET, POST, OPTIONS'
      });
      return response.end();
    }

    const url = new URL(request.url || '/', 'http://localhost');
    try {
      if (request.method === 'POST' && url.pathname === '/api/pair/request') {
        return await handlePairingRequest(request, response);
      }
      if (request.method === 'GET' && url.pathname === '/api/pair/status') {
        return await handlePairingStatus(response, url);
      }
      if (request.method === 'GET' && url.pathname === '/api/pair/requests') {
        return await listPairingRequests(request, response);
      }
      if (request.method === 'POST' && url.pathname === '/api/pair/approve') {
        return await decidePairing(request, response, 'approve');
      }
      if (request.method === 'POST' && url.pathname === '/api/pair/deny') {
        return await decidePairing(request, response, 'deny');
      }
      if (request.method === 'POST' && url.pathname === '/api/telemetry') {
        return await handleTelemetry(request, response);
      }
      if (request.method === 'POST' && url.pathname === '/api/frame') {
        return await handleFrame(request, response, url);
      }
      if (request.method === 'GET' && url.pathname === '/api/devices') {
        const devices = [...latest.entries()].map(([deviceId, value]) => ({
          deviceId,
          deviceName: value.deviceName || deviceId,
          lastSeen: value.receivedAt
        }));
        return jsonResponse(response, 200, { devices });
      }
      if (request.method === 'GET' && url.pathname === '/api/status') {
        await pairingsReady;
        cleanPairingRequests();
        return jsonResponse(response, 200, {
          ok: true,
          devices: latest.size,
          pairedDevices: pairedDevices.size,
          pendingPairingRequests: [...pairingRequests.values()].filter(value => value.status === 'pending').length,
          receivedTelemetry,
          receivedFrames,
          applicationEncryption: 'Per-phone AES-256-GCM required',
          startedAt: server.startedAt
        });
      }
      if (request.method === 'GET' && url.pathname === '/api/latest') {
        const requestedId = safeDeviceId(url.searchParams.get('deviceId'));
        const value = latest.get(requestedId) || [...latest.values()].at(-1);
        return value ? jsonResponse(response, 200, value) : jsonResponse(response, 404, { error: 'No telemetry received yet' });
      }
      if (request.method === 'GET' && url.pathname === '/api/history') {
        const deviceId = safeDeviceId(url.searchParams.get('deviceId'));
        const limit = Math.min(600, Math.max(1, Number(url.searchParams.get('limit')) || 120));
        return jsonResponse(response, 200, { deviceId, samples: (histories.get(deviceId) || []).slice(-limit) });
      }
      if (request.method === 'GET' && url.pathname === '/api/frame/latest') {
        const deviceId = safeDeviceId(url.searchParams.get('deviceId'));
        const camera = safeCamera(url.searchParams.get('camera'));
        const value = latestFrames.get(`${deviceId}:${camera}`)
          || [...latestFrames.values()].reverse().find(frame => frame.camera === camera);
        if (!value) return jsonResponse(response, 404, { error: 'No camera frame received yet' });
        response.writeHead(200, {
          'Cache-Control': 'no-store',
          'Content-Length': value.frame.length,
          'Content-Type': 'image/jpeg',
          'X-Received-At': value.receivedAt
        });
        return response.end(value.frame);
      }
      if (request.method === 'GET' && url.pathname === '/api/video.mjpeg') {
        const deviceId = url.searchParams.get('deviceId') ? safeDeviceId(url.searchParams.get('deviceId')) : '';
        const camera = safeCamera(url.searchParams.get('camera'));
        response.writeHead(200, {
          'Cache-Control': 'no-cache, no-store',
          Connection: 'keep-alive',
          'Content-Type': 'multipart/x-mixed-replace; boundary=sensor-frame'
        });
        const client = { deviceId, camera, response };
        mjpegClients.add(client);
        const initial = deviceId
          ? latestFrames.get(`${deviceId}:${camera}`)
          : [...latestFrames.values()].reverse().find(frame => frame.camera === camera);
        if (initial) emitFrame(initial.deviceId, camera, initial.frame);
        request.on('close', () => mjpegClients.delete(client));
        return;
      }
      if (request.method === 'GET' && url.pathname === '/events') {
        const deviceId = url.searchParams.get('deviceId') ? safeDeviceId(url.searchParams.get('deviceId')) : '';
        response.writeHead(200, {
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
          'Content-Type': 'text/event-stream; charset=utf-8'
        });
        response.write('retry: 1500\n\n');
        const client = { deviceId, response };
        sseClients.add(client);
        const initial = deviceId ? latest.get(deviceId) : [...latest.values()].at(-1);
        if (initial) sendSse(response, 'telemetry', initial);
        request.on('close', () => sseClients.delete(client));
        return;
      }
      if (request.method === 'GET' && url.pathname === '/app-debug.apk') {
        return await serveApk(response);
      }
      if (request.method === 'GET') return await serveStatic(response, url.pathname);
      jsonResponse(response, 404, { error: 'Not found' });
    } catch (error) {
      if (!error.statusCode || error.statusCode >= 500) console.error(error);
      if (!response.headersSent) {
        jsonResponse(response, error.statusCode || 500, { error: error.message || 'Internal server error' });
      } else {
        response.end();
      }
    }
  };

  const server = options.tls
    ? https.createServer({ ...options.tls, minVersion: 'TLSv1.2' }, requestListener)
    : http.createServer(requestListener);

  server.startedAt = new Date().toISOString();
  const keepAlive = setInterval(() => {
    for (const client of sseClients) client.response.write(': keep-alive\n\n');
  }, 20_000);
  keepAlive.unref();

  return {
    server,
    async close() {
      clearInterval(keepAlive);
      for (const client of sseClients) client.response.end();
      for (const client of mjpegClients) client.response.end();
      await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    }
  };
}
