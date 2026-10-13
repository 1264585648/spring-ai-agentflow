const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const { SseChatClient, CardSubmissionError } = require(process.env.CARD_CLIENT_JS);
const originalFetch = global.fetch;
afterEach(() => { global.fetch = originalFetch; });
const submit = () => new SseChatClient().submitCard('act-1', 'session-A', 'TEST_ACTION', {});

function response(status, body) {
  global.fetch = async () => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

test('successful response preserves receipt', async () => {
  response(200, { code: 200, ticketId: 'T-1' });
  assert.equal((await submit()).ticketId, 'T-1');
});

test('explicit pre-execution validation failure permits correction', async () => {
  response(400, { code: 400, message: 'invalid value', errorType: 'INVALID_SUBMISSION', retryable: true });
  await assert.rejects(submit, e => e instanceof CardSubmissionError && e.retryable);
});

for (const [status, type] of [[403, 'CARD_FORBIDDEN'], [409, 'CARD_STATE_CONFLICT'], [500, 'EXECUTION_UNKNOWN']]) {
  test(`${type} blocks another submission`, async () => {
    response(status, { code: status, message: type, errorType: type, retryable: false });
    await assert.rejects(submit, e => e instanceof CardSubmissionError && !e.retryable && e.errorType === type);
  });
}

test('network failure is ambiguous, never automatically retryable', async () => {
  global.fetch = async () => { throw new TypeError('network disconnected'); };
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
});

test('invalid JSON receipt is ambiguous', async () => {
  global.fetch = async () => new Response('gateway error', { status: 502 });
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
});

test('unstructured JSON receipt is ambiguous', async () => {
  response(200, {});
  await assert.rejects(submit, e => !e.retryable && e.errorType === 'RESPONSE_UNKNOWN');
});

test('server 500 cannot become retryable even with a misleading flag', async () => {
  response(500, { code: 500, message: 'failed', retryable: true });
  await assert.rejects(submit, e => !e.retryable);
});
