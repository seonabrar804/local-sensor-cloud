#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSensorCloudServer } from './sensor-cloud.js';

const serverDirectory = path.dirname(fileURLToPath(import.meta.url));
const host = process.env.SENSOR_CLOUD_HOST || '0.0.0.0';
const port = Number(process.env.SENSOR_CLOUD_PORT || 8787);
const dataDirectory = process.env.SENSOR_CLOUD_DATA || path.join(serverDirectory, 'data');
const certificatePath = process.env.SENSOR_CLOUD_CERT || path.join(serverDirectory, 'tls', 'server-cert.pem');
const privateKeyPath = process.env.SENSOR_CLOUD_KEY || path.join(serverDirectory, 'tls', 'server-key.pem');
const applicationKeyPath = process.env.SENSOR_CLOUD_APP_KEY || path.join(serverDirectory, 'keys', 'application-aes.key');
const apkPath = process.env.SENSOR_CLOUD_APK
  || path.join(serverDirectory, '..', 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const [cert, key, applicationEncryptionKey] = await Promise.all([
  readFile(certificatePath),
  readFile(privateKeyPath),
  readFile(applicationKeyPath)
]);

if (applicationEncryptionKey.length !== 32) {
  throw new Error(`Application AES key must contain exactly 32 bytes: ${applicationKeyPath}`);
}

const cloud = createSensorCloudServer({
  apkPath,
  dataDirectory,
  tls: { cert, key },
  applicationEncryptionKey
});

cloud.server.listen(port, host, () => {
  const address = cloud.server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  const browserHost = host === '0.0.0.0' || host === '::' ? 'localhost' : host;
  console.log(`Encrypted Local Sensor Cloud listening on https://${host}:${actualPort}`);
  console.log('Application payload encryption: AES-256-GCM required');
  console.log(`Android APK download: https://${browserHost}:${actualPort}/app-debug.apk`);
  console.log(`Data directory: ${dataDirectory}`);
});

const shutdown = async () => {
  console.log('\nStopping Local Sensor Cloud...');
  await cloud.close();
  process.exit(0);
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
