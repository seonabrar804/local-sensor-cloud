#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { createSensorCloudServer } from './sensor-cloud.js';

const host = process.env.SENSOR_CLOUD_HOST || '0.0.0.0';
const port = Number(process.env.SENSOR_CLOUD_PORT || 8787);
const dataDirectory = process.env.SENSOR_CLOUD_DATA || new URL('./data', import.meta.url).pathname;
const certificatePath = process.env.SENSOR_CLOUD_CERT || new URL('./tls/server-cert.pem', import.meta.url).pathname;
const privateKeyPath = process.env.SENSOR_CLOUD_KEY || new URL('./tls/server-key.pem', import.meta.url).pathname;
const applicationKeyPath = process.env.SENSOR_CLOUD_APP_KEY || new URL('./keys/application-aes.key', import.meta.url).pathname;
const [cert, key, applicationEncryptionKey] = await Promise.all([
  readFile(certificatePath),
  readFile(privateKeyPath),
  readFile(applicationKeyPath)
]);

if (applicationEncryptionKey.length !== 32) {
  throw new Error(`Application AES key must contain exactly 32 bytes: ${applicationKeyPath}`);
}

const cloud = createSensorCloudServer({ dataDirectory, tls: { cert, key }, applicationEncryptionKey });

cloud.server.listen(port, host, () => {
  const address = cloud.server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  console.log(`Encrypted Local Sensor Cloud listening on https://${host}:${actualPort}`);
  console.log('Application payload encryption: AES-256-GCM required');
  console.log(`Data directory: ${dataDirectory}`);
});

const shutdown = async () => {
  console.log('\nStopping Local Sensor Cloud...');
  await cloud.close();
  process.exit(0);
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
