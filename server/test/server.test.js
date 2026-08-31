import assert from 'node:assert/strict';
import { createCipheriv, createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import http from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createSensorCloudServer } from '../sensor-cloud.js';

const TINY_JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
const FRONT_JPEG = Buffer.from([0xff, 0xd8, 0xaa, 0xbb, 0xff, 0xd9]);
const TEST_CERTIFICATE = Buffer.from('test-laptop-certificate-der');

async function startCloud(options = {}) {
  const dataDirectory = await mkdtemp(path.join(tmpdir(), 'sensor-cloud-test-'));
  const cloud = createSensorCloudServer({
    dataDirectory,
    pairingFile: path.join(dataDirectory, 'keys', 'paired-devices.json'),
    serverCertificateDer: TEST_CERTIFICATE,
    ...options
  });
  await new Promise(resolve => cloud.server.listen(0, '127.0.0.1', resolve));
  const { port } = cloud.server.address();
  return { cloud, dataDirectory, origin: `http://127.0.0.1:${port}` };
}

function encryptPayload(key, deviceId, route, plaintext) {
  const iv = Buffer.alloc(12, 0x31);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(`LocalSensorCloud AES-GCM v2\n${deviceId}\n${route}`, 'utf8'));
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([Buffer.from('LSC2'), iv, ciphertext, cipher.getAuthTag()]);
}

function request(origin, route, options = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(route, origin);
    const body = options.body || null;
    const outgoing = http.request(target, {
      method: options.method || 'GET',
      headers: {
        ...(body ? { 'Content-Length': body.length } : {}),
        ...options.headers
      }
    }, incoming => {
      const chunks = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.on('end', () => resolve({
        body: Buffer.concat(chunks),
        headers: incoming.headers,
        status: incoming.statusCode
      }));
    });
    outgoing.on('error', reject);
    if (body) outgoing.write(body);
    outgoing.end();
  });
}

async function pairPhone(origin, deviceId = 'test-phone', decision = 'approve') {
  const clientNonce = Buffer.alloc(32, 0x5a);
  const requested = await request(origin, '/api/pair/request', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: Buffer.from(JSON.stringify({
      clientNonce: clientNonce.toString('base64'),
      deviceId,
      deviceName: 'Lab phone'
    }))
  });
  assert.equal(requested.status, 202);
  const { requestId } = JSON.parse(requested.body);

  const pending = await request(origin, '/api/pair/requests');
  assert.equal(pending.status, 200);
  const pendingRequest = JSON.parse(pending.body).requests.find(value => value.requestId === requestId);
  assert.ok(pendingRequest);
  const digest = createHash('sha256').update(TEST_CERTIFICATE).update(clientNonce).update(requestId).digest();
  assert.equal(pendingRequest.code, String(digest.readUInt32BE(0) % 1_000_000).padStart(6, '0'));

  const decided = await request(origin, `/api/pair/${decision}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Sensor-Dashboard': 'local-approval'
    },
    body: Buffer.from(JSON.stringify({ requestId }))
  });
  assert.equal(decided.status, 200);

  const statusRoute = `/api/pair/status?requestId=${encodeURIComponent(requestId)}`
    + `&deviceId=${encodeURIComponent(deviceId)}`
    + `&clientNonce=${encodeURIComponent(clientNonce.toString('base64'))}`;
  const status = await request(origin, statusRoute);
  assert.equal(status.status, 200);
  const payload = JSON.parse(status.body);
  if (decision === 'deny') return payload;
  assert.equal(payload.status, 'approved');
  const key = Buffer.from(payload.applicationKey, 'base64');
  assert.equal(key.length, 32);
  return { key, requestId };
}

function encryptedHeaders(deviceId) {
  return {
    'Content-Type': 'application/octet-stream',
    'X-Sensor-Device-Id': deviceId,
    'X-Sensor-Encryption': 'aes-256-gcm-v2'
  };
}

test('health endpoint starts empty and ready for phone pairing', async t => {
  const { cloud, origin } = await startCloud();
  t.after(() => cloud.close());
  const response = await request(origin, '/api/status');
  assert.equal(response.status, 200);
  const status = JSON.parse(response.body);
  assert.equal(status.devices, 0);
  assert.equal(status.pairedDevices, 0);
  assert.equal(status.applicationEncryption, 'Per-phone AES-256-GCM required');
});

test('laptop approval pairs a phone and denial does not', async t => {
  const { cloud, origin } = await startCloud();
  t.after(() => cloud.close());
  const denied = await pairPhone(origin, 'denied-phone', 'deny');
  assert.equal(denied.status, 'denied');
  await pairPhone(origin, 'approved-phone');
  const status = JSON.parse((await request(origin, '/api/status')).body);
  assert.equal(status.pairedDevices, 1);
  assert.equal(status.pendingPairingRequests, 0);
});

test('paired telemetry is decrypted, stored, and queryable', async t => {
  const { cloud, origin, dataDirectory } = await startCloud();
  t.after(() => cloud.close());
  const deviceId = 'test-phone';
  const { key } = await pairPhone(origin, deviceId);
  const telemetry = { deviceId, deviceName: 'Lab phone', noise: { dbfs: -31.4 }, sensors: [] };
  const route = '/api/telemetry';
  const accepted = await request(origin, route, {
    method: 'POST',
    headers: encryptedHeaders(deviceId),
    body: encryptPayload(key, deviceId, route, Buffer.from(JSON.stringify(telemetry)))
  });
  assert.equal(accepted.status, 202);

  const latest = JSON.parse((await request(origin, '/api/latest?deviceId=test-phone')).body);
  assert.equal(latest.deviceId, deviceId);
  assert.equal(latest.noise.dbfs, -31.4);
  const devices = JSON.parse((await request(origin, '/api/devices')).body);
  assert.equal(devices.devices[0].deviceName, 'Lab phone');
  const log = await readFile(path.join(dataDirectory, 'telemetry', `${new Date().toISOString().slice(0, 10)}.jsonl`), 'utf8');
  assert.match(log, /"deviceName":"Lab phone"/);
});

test('paired front and back JPEG photos are stored and returned', async t => {
  const { cloud, origin, dataDirectory } = await startCloud();
  t.after(() => cloud.close());
  const deviceId = 'camera-phone';
  const { key } = await pairPhone(origin, deviceId);

  const backRoute = `/api/frame?deviceId=${deviceId}&capture=photo`;
  const accepted = await request(origin, backRoute, {
    method: 'POST',
    headers: encryptedHeaders(deviceId),
    body: encryptPayload(key, deviceId, backRoute, TINY_JPEG)
  });
  assert.equal(accepted.status, 202);
  const receipt = JSON.parse(accepted.body);
  assert.equal(receipt.camera, 'back');
  assert.ok(receipt.photoFile.endsWith('.jpg'));
  assert.deepEqual(await readFile(path.join(dataDirectory, 'photos', receipt.photoFile)), TINY_JPEG);

  const frontRoute = `/api/frame?deviceId=${deviceId}&camera=front`;
  const frontAccepted = await request(origin, frontRoute, {
    method: 'POST',
    headers: encryptedHeaders(deviceId),
    body: encryptPayload(key, deviceId, frontRoute, FRONT_JPEG)
  });
  assert.equal(frontAccepted.status, 202);
  assert.deepEqual((await request(origin, `/api/frame/latest?deviceId=${deviceId}&camera=front`)).body, FRONT_JPEG);
  assert.deepEqual((await request(origin, `/api/frame/latest?deviceId=${deviceId}&camera=back`)).body, TINY_JPEG);
});

test('built Android APK is available as a direct download', async t => {
  const dataDirectory = await mkdtemp(path.join(tmpdir(), 'sensor-cloud-apk-test-'));
  const apkPath = path.join(dataDirectory, 'app-debug.apk');
  const apk = Buffer.from('test Android package');
  await writeFile(apkPath, apk);
  const { cloud, origin } = await startCloud({ apkPath });
  t.after(() => cloud.close());

  const response = await request(origin, '/app-debug.apk');
  assert.equal(response.status, 200);
  assert.equal(response.headers['content-type'], 'application/vnd.android.package-archive');
  assert.equal(response.headers['content-disposition'], 'attachment; filename="LocalSensorCloud-debug.apk"');
  assert.deepEqual(response.body, apk);
});

test('unpaired, plaintext, wrong-identity, and tampered uploads are rejected', async t => {
  const { cloud, origin } = await startCloud();
  t.after(() => cloud.close());
  const route = '/api/telemetry';
  const deviceId = 'encrypted-phone';
  const plaintext = Buffer.from(JSON.stringify({ deviceId, deviceName: 'Encrypted phone', sensors: [] }));

  const unpaired = await request(origin, route, {
    method: 'POST', headers: encryptedHeaders(deviceId), body: Buffer.from('not encrypted')
  });
  assert.equal(unpaired.status, 401);

  const { key } = await pairPhone(origin, deviceId);
  const plaintextResponse = await request(origin, route, {
    method: 'POST', headers: { 'X-Sensor-Device-Id': deviceId }, body: plaintext
  });
  assert.equal(plaintextResponse.status, 415);

  const encrypted = encryptPayload(key, deviceId, route, plaintext);
  const accepted = await request(origin, route, {
    method: 'POST', headers: encryptedHeaders(deviceId), body: encrypted
  });
  assert.equal(accepted.status, 202);

  const wrongIdentity = await request(origin, route, {
    method: 'POST', headers: encryptedHeaders('different-phone'), body: encrypted
  });
  assert.equal(wrongIdentity.status, 401);

  const tampered = Buffer.from(encrypted);
  tampered[tampered.length - 1] ^= 1;
  const rejected = await request(origin, route, {
    method: 'POST', headers: encryptedHeaders(deviceId), body: tampered
  });
  assert.equal(rejected.status, 400);
});
