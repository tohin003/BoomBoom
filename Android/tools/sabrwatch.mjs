// Watches the hidden tab's media requests through the WebView's debugger and prints, for each answer, the kinds of
// parts in it (never the audio itself) and what the server's pacing parts say.
//   node sabrwatch.mjs [port] [seconds]
const port = process.argv[2] || '9333';
const seconds = Number(process.argv[3] || 120);
const pages = await (await fetch(`http://localhost:${port}/json`)).json();
const page = pages.find((p) => p.type === 'page' && /youtube/.test(p.url)) || pages[0];
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
let nextId = 1;
const waiting = new Map();
const send = (method, params = {}) => new Promise((resolve) => {
  const id = nextId++;
  waiting.set(id, resolve);
  socket.send(JSON.stringify({ id, method, params }));
});
const started = Date.now();
const at = () => ((Date.now() - started) / 1000).toFixed(1).padStart(6);
const requests = new Map();

const NAMES = {
  20: 'MEDIA_HEADER', 21: 'MEDIA', 22: 'MEDIA_END', 35: 'NEXT_REQUEST_POLICY', 42: 'FORMAT_INIT', 43: 'SABR_REDIRECT',
  44: 'SABR_ERROR', 45: 'SABR_SEEK', 46: 'RELOAD_PLAYER_RESPONSE', 47: 'PLAYBACK_START_POLICY', 52: 'REQUEST_ID',
  53: 'CANCELLATION_POLICY', 57: 'CONTEXT_UPDATE', 58: 'STREAM_PROTECTION_STATUS', 59: 'CONTEXT_SENDING_POLICY',
  61: 'SABR_ACK', 62: 'END_OF_TRACK', 66: 'DEBUG_INFO', 67: 'SNACKBAR',
};

function umpVarint(bytes, offset) {
  const first = bytes[offset];
  if (first < 0x80) return [first, 1];
  if (first < 0xc0) return [(first & 0x3f) + 64 * bytes[offset + 1], 2];
  if (first < 0xe0) return [(first & 0x1f) + 32 * (bytes[offset + 1] + 256 * bytes[offset + 2]), 3];
  if (first < 0xf0) return [(first & 0x0f) + 16 * (bytes[offset + 1] + 256 * (bytes[offset + 2] + 256 * bytes[offset + 3])), 4];
  return [bytes[offset + 1] + 256 * (bytes[offset + 2] + 256 * (bytes[offset + 3] + 256 * bytes[offset + 4])), 5];
}

// The top-level numeric fields of a protobuf message (enough for the pacing parts).
function protoNumbers(bytes) {
  const out = {};
  let i = 0;
  while (i < bytes.length) {
    let key = 0, shift = 0;
    while (i < bytes.length) { const b = bytes[i++]; key |= (b & 0x7f) << shift; shift += 7; if (b < 0x80) break; }
    const field = key >>> 3, wire = key & 7;
    if (wire === 0) {
      let value = 0, mul = 1;
      while (i < bytes.length) { const b = bytes[i++]; value += (b & 0x7f) * mul; mul *= 128; if (b < 0x80) break; }
      out[field] = value;
    } else if (wire === 2) {
      let length = 0, s = 0;
      while (i < bytes.length) { const b = bytes[i++]; length |= (b & 0x7f) << s; s += 7; if (b < 0x80) break; }
      out[field] = `<${length} bytes>`;
      i += length;
    } else if (wire === 5) { i += 4; out[field] = '<fixed32>'; } else if (wire === 1) { i += 8; out[field] = '<fixed64>'; } else break;
  }
  return out;
}

function describe(bytes) {
  const parts = [];
  const notes = [];
  let media = 0;
  let offset = 0;
  while (offset < bytes.length) {
    const [type, a] = umpVarint(bytes, offset); offset += a;
    if (offset >= bytes.length) break;
    const [size, b] = umpVarint(bytes, offset); offset += b;
    const payload = bytes.subarray(offset, Math.min(bytes.length, offset + size));
    offset += size;
    if (type === 21) { media += size; continue; }
    parts.push(NAMES[type] || `#${type}`);
    if (type === 35 || type === 58 || type === 44 || type === 45 || type === 47 || type === 53) {
      notes.push(`${NAMES[type]} ${JSON.stringify(protoNumbers(payload))}`);
    }
  }
  return { parts, notes, media };
}

socket.onmessage = async (event) => {
  const message = JSON.parse(event.data);
  if (message.id && waiting.has(message.id)) { waiting.get(message.id)(message); waiting.delete(message.id); return; }
  const p = message.params || {};
  if (message.method === 'Network.requestWillBeSent' && /videoplayback/.test(p.request.url)) {
    requests.set(p.requestId, { sent: Date.now(), body: (p.request.postData || '').length, hasBody: !!p.request.hasPostData });
    console.log(`${at()} -> request ${p.requestId.slice(-6)} post=${p.request.hasPostData ? 'yes' : 'no'}`);
  } else if (message.method === 'Network.loadingFinished' && requests.has(p.requestId)) {
    const info = requests.get(p.requestId);
    const body = await send('Network.getResponseBody', { requestId: p.requestId });
    let line = `${at()} <- answer  ${p.requestId.slice(-6)} ${((Date.now() - info.sent) / 1000).toFixed(2)} s, ${p.encodedDataLength} bytes`;
    if (body.result && body.result.body != null) {
      const bytes = body.result.base64Encoded ? Buffer.from(body.result.body, 'base64') : Buffer.from(body.result.body, 'binary');
      const d = describe(bytes);
      line += ` media=${d.media} parts=[${d.parts.join(',')}]`;
      console.log(line);
      for (const note of d.notes) console.log(`         ${note}`);
    } else {
      console.log(line + ' (body not kept)');
    }
    requests.delete(p.requestId);
  } else if (message.method === 'Network.loadingFailed' && requests.has(p.requestId)) {
    console.log(`${at()} xx failed  ${p.requestId.slice(-6)} ${p.errorText}${p.canceled ? ' (cancelled)' : ''}`);
    requests.delete(p.requestId);
  }
};
await send('Network.enable', { maxTotalBufferSize: 200_000_000, maxResourceBufferSize: 20_000_000 });
console.log(`${at()} watching ${page.url.slice(0, 60)}`);
setTimeout(() => { socket.close(); process.exit(0); }, seconds * 1000);
