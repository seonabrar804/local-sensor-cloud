const elements = {
  battery: document.querySelector('#batteryValue'),
  backCameraEmpty: document.querySelector('#backCameraEmpty'),
  backCameraTitle: document.querySelector('#backCameraTitle'),
  backFrameRate: document.querySelector('#backFrameRate'),
  backVideo: document.querySelector('#backVideoStream'),
  connection: document.querySelector('.connection'),
  connectionLabel: document.querySelector('#connectionLabel'),
  device: document.querySelector('#deviceSelect'),
  frontCameraEmpty: document.querySelector('#frontCameraEmpty'),
  frontCameraTitle: document.querySelector('#frontCameraTitle'),
  frontFrameRate: document.querySelector('#frontFrameRate'),
  frontVideo: document.querySelector('#frontVideoStream'),
  lastSeen: document.querySelector('#lastSeen'),
  light: document.querySelector('#lightValue'),
  noise: document.querySelector('#noiseValue'),
  noiseChart: document.querySelector('#noiseChart'),
  pressure: document.querySelector('#pressureValue'),
  sensorCount: document.querySelector('#sensorCount'),
  sensorRows: document.querySelector('#sensorRows'),
  temperature: document.querySelector('#temperatureValue')
};

let source;
let selectedDevice = '';
let noiseHistory = [];
let frameTimes = { back: [], front: [] };

const format = (value, decimals = 1) => Number.isFinite(Number(value)) ? Number(value).toFixed(decimals) : '—';
const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
}[character]));

function measurementByType(telemetry, type) {
  return (telemetry.sensors || []).find(sensor => sensor.type === type)?.values?.[0];
}

function drawNoiseChart() {
  const canvas = elements.noiseChart;
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const context = canvas.getContext('2d');
  context.scale(ratio, ratio);
  context.clearRect(0, 0, width, height);
  if (noiseHistory.length < 2) return;

  const gradient = context.createLinearGradient(0, 0, 0, height);
  gradient.addColorStop(0, '#74ed9f');
  gradient.addColorStop(1, '#245f3b');
  context.strokeStyle = gradient;
  context.lineWidth = 2;
  context.beginPath();
  noiseHistory.forEach((value, index) => {
    const x = index / Math.max(1, noiseHistory.length - 1) * width;
    const y = height - Math.min(1, Math.max(0, (value + 90) / 90)) * (height - 6) - 3;
    if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
  });
  context.stroke();
}

function updateTelemetry(value) {
  elements.connection.classList.add('live');
  elements.connectionLabel.textContent = `Receiving ${value.deviceName || value.deviceId}`;
  elements.lastSeen.textContent = new Date(value.receivedAt || value.sentAt).toLocaleTimeString();
  elements.noise.textContent = format(value.noise?.dbfs, 1);
  elements.pressure.textContent = format(value.readings?.pressureHpa ?? measurementByType(value, 6), 1);
  elements.temperature.textContent = format(value.readings?.ambientTemperatureC ?? measurementByType(value, 13), 1);
  elements.light.textContent = format(value.readings?.lightLux ?? measurementByType(value, 5), 0);
  elements.battery.textContent = format(value.device?.batteryPercent, 0);

  if (Number.isFinite(value.noise?.dbfs)) {
    noiseHistory.push(value.noise.dbfs);
    noiseHistory = noiseHistory.slice(-90);
    drawNoiseChart();
  }

  const sensors = Array.isArray(value.sensors) ? value.sensors : [];
  elements.sensorCount.textContent = `${sensors.length} sensor${sensors.length === 1 ? '' : 's'}`;
  elements.sensorRows.innerHTML = sensors.length ? sensors.map(sensor => `
    <tr>
      <td><strong>${escapeHtml(sensor.name)}</strong><small>${escapeHtml(sensor.vendor)}</small></td>
      <td>${escapeHtml(sensor.stringType || `type ${sensor.type}`)}${sensor.unit ? `<br><small>${escapeHtml(sensor.unit)}</small>` : ''}</td>
      <td class="values">${(sensor.values || []).map(number => format(number, Math.abs(number) >= 100 ? 1 : 3)).join(' · ')}</td>
      <td>${escapeHtml(sensor.accuracyLabel || sensor.accuracy)}</td>
    </tr>`).join('') : '<tr><td colspan="4" class="table-empty">No measurements received</td></tr>';
}

function noteFrame(frame) {
  const camera = frame.camera === 'front' ? 'front' : 'back';
  const now = Date.now();
  frameTimes[camera].push(now);
  frameTimes[camera] = frameTimes[camera].filter(time => time > now - 5000);
  const fps = Math.max(0, (frameTimes[camera].length - 1) / 5);
  elements[`${camera}FrameRate`].textContent = `${fps.toFixed(1)} fps`;
  elements[`${camera}CameraTitle`].textContent = frame.capture === 'photo' ? 'Photo captured' : 'Streaming';
  elements[`${camera}CameraEmpty`].hidden = true;
}

function connect(deviceId) {
  if (source) source.close();
  selectedDevice = deviceId;
  noiseHistory = [];
  frameTimes = { back: [], front: [] };
  if (!deviceId) return;
  const encoded = encodeURIComponent(deviceId);
  elements.backVideo.src = `/api/video.mjpeg?deviceId=${encoded}&camera=back&v=${Date.now()}`;
  elements.frontVideo.src = `/api/video.mjpeg?deviceId=${encoded}&camera=front&v=${Date.now()}`;
  source = new EventSource(`/events?deviceId=${encoded}`);
  source.addEventListener('telemetry', event => updateTelemetry(JSON.parse(event.data)));
  source.addEventListener('frame', event => noteFrame(JSON.parse(event.data)));
  source.onerror = () => {
    elements.connection.classList.remove('live');
    elements.connectionLabel.textContent = 'Reconnecting…';
  };
}

async function refreshDevices() {
  try {
    const response = await fetch('/api/devices', { cache: 'no-store' });
    const { devices } = await response.json();
    const previous = selectedDevice || elements.device.value;
    elements.device.innerHTML = devices.length
      ? devices.map(device => `<option value="${escapeHtml(device.deviceId)}">${escapeHtml(device.deviceName)} · ${escapeHtml(device.deviceId)}</option>`).join('')
      : '<option value="">No device connected</option>';
    const next = devices.some(device => device.deviceId === previous) ? previous : devices[0]?.deviceId || '';
    elements.device.value = next;
    if (next !== selectedDevice) connect(next);
  } catch {
    elements.connection.classList.remove('live');
    elements.connectionLabel.textContent = 'Server unavailable';
  }
}

elements.device.addEventListener('change', () => connect(elements.device.value));
window.addEventListener('resize', drawNoiseChart);
refreshDevices();
setInterval(refreshDevices, 4000);
