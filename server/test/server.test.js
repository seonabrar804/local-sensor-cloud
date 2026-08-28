import assert from 'node:assert/strict';
import { createCipheriv } from 'node:crypto';
import { mkdtemp, readFile } from 'node:fs/promises';
import http from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createSensorCloudServer } from '../sensor-cloud.js';

const TINY_JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
const FRONT_JPEG = Buffer.from([0xff, 0xd8, 0xaa, 0xbb, 0xff, 0xd9]);

async function startCloud(options = {}) {
  const dataDirectory = await mkdtemp(path.join(tmpdir(), 'sensor-cloud-test-'));
  const cloud = createSensorCloudServer({ dataDirectory, ...options });
  await new Promise(resolve => cloud.server.listen(0, '127.0.0.1', resolve));
  const { port } = cloud.server.address();
  return { cloud, dataDirectory, origin: `http://127.0.0.1:${port}` };
}

function encryptPayload(key, route, plaintext) {
  const iv = Buffer.alloc(12, 0x31);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(`LocalSensorCloud AES-GCM v1\n${route}`, 'utf8'));
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([Buffer.from('LSC1'), iv, ciphertext, cipher.getAuthTag()]);
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

test('health endpoint starts empty', async t => {
  const { cloud, origin } = await startCloud();
  t.after(() => cloud.close());
  const response = await request(origin, '/api/status');
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(response.body).devices, 0);
});

test('telemetry is accepted by laptop address and becomes queryable', async t => {
  const { cloud, origin, dataDirectory } = await startCloud();
  t.after(() => cloud.close());
  const telemetry = { deviceId: 'test phone', deviceName: 'Lab phone', noise: { dbfs: -31.4 }, sensors: [] };

  const telemetryBody = Buffer.from(JSON.stringify(telemetry));
  const accepted = await request(origin, '/api/telemetry', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: telemetryBody
  });
  assert.equal(accepted.status, 202);

  const latest = JSON.parse((await request(origin, '/api/latest?deviceId=test_phone')).body);
  assert.equal(latest.deviceId, 'test_phone');
  assert.equal(latest.noise.dbfs, -31.4);

  const devices = JSON.parse((await request(origin, '/api/devices')).body);
  assert.equal(devices.devices[0].deviceName, 'Lab phone');
  const log = await readFile(path.join(dataDirectory, 'telemetry', `${new Date().toISOString().slice(0, 10)}.jsonl`), 'utf8');
  assert.match(log, /"deviceName":"Lab phone"/);
});

test('JPEG frames are stored and returned', async t => {
  const { cloud, origin, dataDirectory } = await startCloud();
  t.after(() => cloud.close());

  const accepted = await request(origin, '/api/frame?deviceId=test-phone&capture=photo', {
    method: 'POST',
    headers: { 'Content-Type': 'image/jpeg' },
    body: TINY_JPEG
  });
  assert.equal(accepted.status, 202);
  const receipt = JSON.parse(accepted.body);
  assert.equal(receipt.camera, 'back');
  assert.equal(receipt.capture, 'photo');
  assert.ok(receipt.photoFile.endsWith('.jpg'));

  const frame = (await request(origin, '/api/frame/latest?deviceId=test-phone')).body;
  assert.deepEqual(frame, TINY_JPEG);
  assert.deepEqual(await readFile(path.join(dataDirectory, 'photos', receipt.photoFile)), TINY_JPEG);

  const frontAccepted = await request(origin, '/api/frame?deviceId=test-phone&camera=front', {
    method: 'POST',
    headers: { 'Content-Type': 'image/jpeg' },
    body: FRONT_JPEG
  });
  assert.equal(frontAccepted.status, 202);
  assert.equal(JSON.parse(frontAccepted.body).camera, 'front');
  const frontFrame = (await request(origin, '/api/frame/latest?deviceId=test-phone&camera=front')).body;
  assert.deepEqual(frontFrame, FRONT_JPEG);
  const backFrame = (await request(origin, '/api/frame/latest?deviceId=test-phone&camera=back')).body;
  assert.deepEqual(backFrame, TINY_JPEG);
});

test('AES-GCM uploads are decrypted and plaintext or tampering is rejected', async t => {
  const applicationEncryptionKey = Buffer.alloc(32, 0x42);
  const { cloud, origin } = await startCloud({ applicationEncryptionKey });
  t.after(() => cloud.close());
  const route = '/api/telemetry';
  const plaintext = Buffer.from(JSON.stringify({
    deviceId: 'encrypted-phone',
    deviceName: 'Encrypted phone',
    sensors: []
  }));

  const plaintextResponse = await request(origin, route, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: plaintext
  });
  assert.equal(plaintextResponse.status, 415);

  const encrypted = encryptPayload(applicationEncryptionKey, route, plaintext);
  const accepted = await request(origin, route, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Sensor-Encryption': 'aes-256-gcm-v1'
    },
    body: encrypted
  });
  assert.equal(accepted.status, 202);
  const latest = JSON.parse((await request(origin, '/api/latest?deviceId=encrypted-phone')).body);
  assert.equal(latest.deviceName, 'Encrypted phone');

  const frameRoute = '/api/frame?deviceId=encrypted-phone&camera=front&capture=photo';
  const encryptedFrame = encryptPayload(applicationEncryptionKey, frameRoute, FRONT_JPEG);
  const frameAccepted = await request(origin, frameRoute, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Sensor-Encryption': 'aes-256-gcm-v1'
    },
    body: encryptedFrame
  });
  assert.equal(frameAccepted.status, 202);
  const receivedFrame = (await request(origin, '/api/frame/latest?deviceId=encrypted-phone&camera=front')).body;
  assert.deepEqual(receivedFrame, FRONT_JPEG);

  const tampered = Buffer.from(encrypted);
  tampered[tampered.length - 1] ^= 1;
  const rejected = await request(origin, route, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Sensor-Encryption': 'aes-256-gcm-v1'
    },
    body: tampered
  });
  assert.equal(rejected.status, 400);
});
