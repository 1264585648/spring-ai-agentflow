const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const {SseChatClient, CardSubmissionError} = require(process.env.CARD_CLIENT_JS);
const originalFetch = global.fetch;
const originalSource = global.EventSource;
afterEach(() => { global.fetch = originalFetch; global.EventSource = originalSource; });
class Source {
  static OPEN = 1;
  static latest;
  readyState = 1;
  listeners = new Map();
  constructor(url) { this.url = url; Source.latest = this; }
  addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
  close() { this.readyState = 2; }
  emit(type, data, id = '') {
    const event = {data: JSON.stringify(data), lastEventId: id};
    (this.listeners.get(type) || []).forEach(fn => fn(event));
    if (type === 'error') this.onerror?.(event);
  }
}
function setup() {
  global.EventSource = Source;
  const received = [], transportErrors = [];
  const client = new SseChatClient();
  client.connect('session-A', (type, data) => received.push({type, data}), e => transportErrors.push(e));
  return {client, source: Source.latest, received, transportErrors};
}
test('same timestamp, different sequences preserve both chunks', () => {
  const {source, received} = setup();
  source.emit('message', {turnId:'t', sequence:1, content:'A'}, '1700000000000');
  source.emit('message', {turnId:'t', sequence:2, content:'B'}, '1700000000000');
  assert.equal(received.map(e => e.data.content).join(''), 'AB');
});
test('done survives same timestamp as final message', () => {
  const {source, received} = setup();
  source.emit('message', {turnId:'t', sequence:1, content:'A'}, '1700000000000');
  source.emit('done', {turnId:'t', sequence:2}, '1700000000000');
  assert.deepEqual(received.map(e => e.type), ['message','done']);
});
test('actual replay is deduplicated, including reconnect', () => {
  const {client, source, received} = setup();
  source.emit('message', {turnId:'t', sequence:1, content:'A'}, 'same');
  client.connect('session-A', (type, data) => received.push({type, data}));
  Source.latest.emit('message', {turnId:'t', sequence:1, content:'A'}, 'same');
  assert.equal(received.length, 1);
});
test('business error does not mark transport disconnected', () => {
  const {client, source, received, transportErrors} = setup();
  source.emit('error', {turnId:'t', sequence:1, message:'failed'});
  assert.equal(received.length, 1);
  assert.equal(transportErrors.length, 0);
  assert.equal(client.getConnectionState(), 'ready');
});
test('old source callbacks cannot affect replacement', () => {
  const {client, source, received} = setup();
  client.connect('session-B', (type, data) => received.push({type, data}));
  source.emit('message', {turnId:'old', sequence:1}); source.onerror?.({});
  assert.equal(received.length, 0);
  assert.equal(client.getConnectionState(), 'connecting');
});
test('close rejects ready waiters immediately', async () => {
  const {client, source} = setup(); source.readyState = 0;
  const waiting = client.waitForReady(5000); client.close();
  await assert.rejects(waiting, /断开/);
  assert.equal(client.readyWaiters.size, 0);
});
test('deadline aborts stalled body and caller does not wait for it', async () => {
  let signal;
  global.fetch = async (_url, init) => {
    signal = init.signal;
    return {ok:true, status:200, text:() => new Promise(() => {})};
  };
  const client = new SseChatClient();
  await assert.rejects(client.requestJson('/test', {}, 20), /超时/);
  assert.equal(signal.aborted, true);
});
const submit = () => new SseChatClient(30).submitCard('act-1','s','TEST_ACTION',{});
function response(status, body) {
  global.fetch = async () => new Response(JSON.stringify(body), {status, headers:{'Content-Type':'application/json'}});
}
test('successful card response preserves receipt', async () => {
  response(200, {code:200, ticketId:'T-1'}); assert.equal((await submit()).ticketId, 'T-1');
});
test('only explicit pre-execution validation failure permits correction', async () => {
  response(400, {code:400, message:'invalid', errorType:'INVALID_SUBMISSION', retryable:true});
  await assert.rejects(submit, e => e instanceof CardSubmissionError && e.retryable);
});
for (const [status, type] of [[403,'CARD_FORBIDDEN'],[409,'CARD_STATE_CONFLICT'],[500,'EXECUTION_UNKNOWN']]) {
  test(`${type} never permits another execution`, async () => {
    response(status, {code:status, message:type, errorType:type, retryable:true});
    await assert.rejects(submit, e => !e.retryable && e.errorType === type);
  });
}
for (const body of [{}, {code:200}, {code:200,ticketId:''}, null]) {
  test(`incomplete receipt ${JSON.stringify(body)} is unknown`, async () => {
    response(200, body); await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
  });
}
test('non JSON or lost response never suggests retry', async () => {
  global.fetch = async () => new Response('gateway HTML', {status:502});
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
  global.fetch = async () => { throw new TypeError('network lost'); };
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
});
test('card body timeout is an unknown execution outcome', async () => {
  global.fetch = async () => ({ok:true,status:200,text:() => new Promise(() => {})});
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
});
