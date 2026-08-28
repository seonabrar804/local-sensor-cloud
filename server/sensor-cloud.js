import http from 'node:http';
import https from 'node:https';
import { createDecipheriv } from 'node:crypto';
import { appendFile, mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_DIRECTORY = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');
const JSON_LIMIT = 1024 * 1024;
const FRAME_LIMIT = 5 * 1024 * 1024;
const HISTORY_LIMIT = 600;
const APPLICATION_ENCRYPTION_HEADER = 'aes-256-gcm-v1';
const APPLICATION_ENCRYPTION_MAGIC = Buffer.from('LSC1');
const APPLICATION_IV_LENGTH = 12;
const APPLICATION_TAG_LENGTH = 16;
const APPLICATION_AAD_PREFIX = 'LocalSensorCloud AES-GCM v1\n';

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
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
    'Content-Type': 'application/json; charset=utf-8'
  });
  response.end(body);
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

function isoFileTimestamp(date = new Date()) {
  return date.toISOString().replaceAll(':', '-').replaceAll('.', '-');
}

function sendSse(response, event, value) {
  response.write(`event: ${event}\ndata: ${JSON.stringify(value)}\n\n`);
}

export function createSensorCloudServer(options = {}) {
  const dataDirectory = path.resolve(options.dataDirectory || './data');
  const publicDirectory = path.resolve(options.publicDirectory || PUBLIC_DIRECTORY);
  const telemetryDirectory = path.join(dataDirectory, 'telemetry');
  const frameDirectory = path.join(dataDirectory, 'frames');
  const photoDirectory = path.join(dataDirectory, 'photos');
  const applicationEncryptionKey = options.applicationEncryptionKey
    ? Buffer.from(options.applicationEncryptionKey)
    : null;
  if (applicationEncryptionKey && applicationEncryptionKey.length !== 32) {
    throw new Error('applicationEncryptionKey must contain exactly 32 bytes');
  }

  const latest = new Map();
  const latestFrames = new Map();
  const histories = new Map();
  const sseClients = new Set();
  const mjpegClients = new Set();
  let receivedTelemetry = 0;
  let receivedFrames = 0;

  const ready = Promise.all([
    mkdir(telemetryDirectory, { recursive: true }),
    mkdir(frameDirectory, { recursive: true }),
    mkdir(photoDirectory, { recursive: true })
  ]);

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
    if (!applicationEncryptionKey) return encryptedBody;
    if (request.headers['x-sensor-encryption'] !== APPLICATION_ENCRYPTION_HEADER) {
      const error = new Error('AES-256-GCM application encryption is required');
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
      const decipher = createDecipheriv('aes-256-gcm', applicationEncryptionKey, iv);
      decipher.setAAD(Buffer.from(APPLICATION_AAD_PREFIX + (request.url || '/'), 'utf8'));
      decipher.setAuthTag(authenticationTag);
      return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    } catch {
      const error = new Error('Encrypted payload authentication failed');
      error.statusCode = 400;
      throw error;
    }
  }

  async function handleTelemetry(request, response) {
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
    const encryptedBody = await readBody(request, FRAME_LIMIT + 64);
    const frame = decryptApplicationPayload(request, encryptedBody);
    if (frame.length < 4 || frame[0] !== 0xff || frame[1] !== 0xd8) {
      return jsonResponse(response, 400, { error: 'Body must be a JPEG image' });
    }

    await ready;
    const deviceId = safeDeviceId(url.searchParams.get('deviceId'));
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

  const requestListener = async (request, response) => {
    response.setHeader('Access-Control-Allow-Origin', '*');
    if (options.tls) response.setHeader('Strict-Transport-Security', 'max-age=31536000');
    if (request.method === 'OPTIONS') {
      response.writeHead(204, {
        'Access-Control-Allow-Headers': 'Content-Type, X-Sensor-Encryption, X-Sensor-Plaintext-Type',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Origin': '*'
      });
      return response.end();
    }

    const url = new URL(request.url || '/', 'http://localhost');
    try {
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
        return jsonResponse(response, 200, {
          ok: true,
          devices: latest.size,
          receivedTelemetry,
          receivedFrames,
          applicationEncryption: applicationEncryptionKey ? 'AES-256-GCM required' : 'disabled',
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
          'Access-Control-Allow-Origin': '*',
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
