const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { cancelTurn } = require(path.join(path.dirname(process.env.CARD_CLIENT_JS), 'turnCancellation.js'));
const originalFetch = global.fetch;
afterEach(() => { global.fetch = originalFetch; });

test('cancellation is an encoded POST bound to session and original turn, independent of SSE readiness', async () => {
  let called;
  global.fetch = async (url, init) => {
    called = { url: new URL(url, 'http://localhost'), init };
    return new Response(JSON.stringify({ code: 200, turnId: 'turn A/&', cancelled: false }), { status: 200 });
  };
  const result = await cancelTurn('session A/&', 'turn A/&');
  assert.equal(called.init.method, 'POST');
  assert.equal(called.url.searchParams.get('sessionId'), 'session A/&');
  assert.equal(called.url.searchParams.get('turnId'), 'turn A/&');
  assert.equal(result.cancelled, false);
});
test('missing turn identity never sends a session-wide cancellation', async () => {
  let calls = 0; global.fetch = async () => { calls++; throw new Error('must not call'); };
  await assert.rejects(cancelTurn('s', ''));
  await assert.rejects(cancelTurn('', 't'));
  await assert.rejects(cancelTurn('s', 'x'.repeat(129)));
  assert.equal(calls, 0);
});
test('wrong turn in receipt is not confirmation of cancellation', async () => {
  global.fetch = async () => new Response(JSON.stringify({ code: 200, turnId: 'another', cancelled: true }), { status: 200 });
  await assert.rejects(cancelTurn('s', 'original'), /不完整|不匹配/);
});
test('HTTP failure and malformed receipt remain unconfirmed', async () => {
  global.fetch = async () => new Response('{}', { status: 503 });
  await assert.rejects(cancelTurn('s', 't'));
  global.fetch = async () => new Response(JSON.stringify({ code: 200, turnId: 't' }), { status: 200 });
  await assert.rejects(cancelTurn('s', 't'));
});
