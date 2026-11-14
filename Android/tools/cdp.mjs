// Evaluates JavaScript in the Android WebView's page through its debugger.  node cdp.mjs '<expression>' [port]
const expression = process.argv[2];
const port = process.argv[3] || '9333';
const pages = await (await fetch(`http://localhost:${port}/json`)).json();
const page = pages.find((p) => p.type === 'page' && /youtube/.test(p.url)) || pages[0];
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
const answer = await new Promise((resolve) => { socket.onmessage = (event) => { const m = JSON.parse(event.data); if (m.id === 1) resolve(m); }; });
const result = answer.result && answer.result.result;
console.log(result ? (typeof result.value === 'string' ? result.value : JSON.stringify(result.value ?? result.description ?? result)) : JSON.stringify(answer));
socket.close();
