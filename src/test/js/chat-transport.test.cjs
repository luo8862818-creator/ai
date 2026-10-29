const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/chat.js'), 'utf8');
const createIdSource = source.slice(source.indexOf('function createSessionId('), source.indexOf('const sessionId ='));

for (const [name, crypto] of [
  ['HTTPS', webcrypto],
  ['HTTP with getRandomValues only', { getRandomValues: bytes => webcrypto.getRandomValues(bytes) }],
  ['no crypto', undefined],
  ['empty crypto', {}],
  ['unavailable APIs', { randomUUID() { throw Error(); }, getRandomValues() { throw Error(); } }]
]) test(`session identifier works: ${name}`, () => {
  const context = vm.createContext({ crypto });
  vm.runInContext(createIdSource, context);
  const ids = Array.from({ length: 1000 }, () => context.createSessionId());
  assert.equal(new Set(ids).size, 1000);
  assert.ok(ids.every(id => /^[a-zA-Z0-9-]{1,64}$/.test(id)));
  if (name.startsWith('HTTP')) assert.match(ids[0], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

const context = vm.createContext({ TextDecoder });
vm.runInContext(source.slice(source.indexOf('async function readEvents('), source.indexOf('form.addEventListener("submit"')), context);
function responseWith(chunks) {
  let index = 0;
  const state = { cancelled: false, released: false, reads: 0 };
  return {state, body: {getReader: () => ({
    async read() {
      state.reads++;
      if (index === chunks.length) throw Error('Must not wait for EOF after terminal event');
      return { value: chunks[index++], done: false };
    },
    async cancel() { state.cancelled = true; },
    releaseLock() { state.released = true; }
  })}};
}
for (const terminal of ['complete', 'error']) test(`stops on ${terminal} and ignores trailing events without waiting for EOF`, async () => {
  const text = `event: answer\r\ndata: {"text":"中文"}\r\n\r\nevent: ${terminal}\ndata: {}\n\nevent: answer\ndata: {"text":"不应显示"}\n\n`;
  const bytes = new TextEncoder().encode(text);
  // Split into individual bytes, including in the middle of multibyte Chinese text.
  const response = responseWith(Array.from(bytes, byte => new Uint8Array([byte])));
  const events = [];
  await context.readEvents(response, (type, data) => events.push([type, data]));
  assert.deepEqual(events.map(e => e[0]), ['answer', terminal]);
  assert.equal(events[0][1].text, '中文');
  assert.ok(response.state.cancelled && response.state.released);
});
test('cleans up reader after invalid event JSON', async () => {
  const response = responseWith([new TextEncoder().encode('event: answer\ndata: INVALID\n\n')]);
  await assert.rejects(context.readEvents(response, () => {}), /格式异常/);
  assert.ok(response.state.cancelled && response.state.released);
});
