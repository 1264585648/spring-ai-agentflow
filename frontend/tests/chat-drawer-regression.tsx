import React, { useState } from 'react';
import { createRoot, Root } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { ChatDrawer } from '../src/components/ChatDrawer';

/** 真实浏览器挂载生产 ChatDrawer；仅替换 HTTP/SSE 传输，不替换 React 或被测组件。 */
class Source extends EventTarget {
  static OPEN = 1;
  static latest: Source;
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  constructor(readonly url: string) {
    super(); Source.latest = this;
    queueMicrotask(() => { if (this.readyState !== 2) this.open(); });
  }
  open() { this.readyState = 1; this.onopen?.(); }
  close() { this.readyState = 2; }
  emit(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data), lastEventId: crypto.randomUUID() });
    this.dispatchEvent(event);
    if (type === 'error') this.onerror?.(event);
  }
}
interface Ask {
  sessionId: string; turnId: string; query: string; settled: boolean;
  resolve: (response: Response) => void; reject: (error: Error) => void;
}
const asks: Ask[] = [];
const cancellations: { sessionId: string | null; turnId: string | null }[] = [];
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
Object.defineProperty(globalThis, 'EventSource', { configurable: true, value: Source });
globalThis.fetch = async (input, init) => {
  const url = new URL(String(input), location.origin);
  if (url.pathname === '/api/v1/commands/palette') return json([]);
  if (url.pathname === '/api/v1/chat/cancel') {
    const request = { sessionId: url.searchParams.get('sessionId'), turnId: url.searchParams.get('turnId') };
    cancellations.push(request);
    return json({ code: 200, turnId: request.turnId, cancelled: false });
  }
  if (url.pathname === '/api/v1/chat/ask') {
    const body = JSON.parse(String(init?.body));
    return new Promise<Response>((resolve, reject) => asks.push({ ...body, settled: false,
      resolve: response => { resolve(response); }, reject: error => { reject(error); } }));
  }
  throw new Error(`Unexpected request: ${url}`);
};

const assert = (condition: unknown, message: string): void => { if (!condition) throw new Error(message); };
const tick = () => new Promise<void>(resolve => setTimeout(resolve, 5));
async function settle() { await tick(); await tick(); await tick(); }
let root: Root | undefined;
let setOpen: (open: boolean) => void = () => { throw new Error('Host not mounted'); };
function Host() {
  const [open, updateOpen] = useState(true);
  setOpen = updateOpen;
  return <ChatDrawer isOpen={open} onClose={() => updateOpen(false)} caseId="test-case" />;
}
function ack(ask: Ask, turnId = ask.turnId) {
  if (ask.settled) return;
  ask.settled = true;
  ask.resolve(json({ code: 200, message: 'accepted', sessionId: ask.sessionId, turnId }));
}
function rejectAsk(ask: Ask) { ask.settled = true; ask.reject(new Error('LATE_OLD_HTTP_FAILURE')); }
async function clean() {
  if (root) flushSync(() => root!.unmount());
  root = undefined;
  asks.forEach(ask => ack(ask));
  await settle();
}
async function fresh() {
  await clean(); asks.length = 0; cancellations.length = 0;
  root = createRoot(document.getElementById('root')!);
  flushSync(() => root!.render(<Host />));
  await settle();
}
const input = () => document.querySelector<HTMLInputElement>('input[aria-label="提问内容"]')!;
const button = () => document.querySelector<HTMLButtonElement>('button[aria-label="发送提问"]')!;
const assistant = (ask: Ask) => document.querySelector<HTMLElement>(`[data-role="assistant"][data-turn-id="${ask.turnId}"]`)!;
const statusText = () => document.querySelector('[role="status"]')?.textContent || '';
function enter(text: string) {
  flushSync(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input(), text);
    input().dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function send(text: string) {
  const count = asks.length;
  assert(input() && !input().disabled, 'Input must be usable before sending');
  enter(text); assert(!button().disabled, 'Send must be enabled');
  flushSync(() => button().click());
  await settle();
  assert(asks.length === count + 1, 'Exactly one ask should reach HTTP');
  return asks[asks.length - 1];
}
async function toggle(open: boolean) { flushSync(() => setOpen(open)); await settle(); }
async function emit(type: string, data: unknown) { flushSync(() => Source.latest.emit(type, data)); await settle(); }

const results: string[] = [];
async function test(name: string, run: () => Promise<void>) {
  await fresh(); await run(); results.push(name); await clean();
}
async function run() {
  await test('close and reopen preserves history and releases the input', async () => {
    const a = await send('first question'); ack(a); await settle();
    assert(input().disabled, 'A remains streaming after HTTP acceptance');
    await toggle(false);
    assert(!input(), 'Closed drawer must be hidden');
    assert(cancellations.some(item => item.turnId === a.turnId && item.sessionId === a.sessionId), 'Close must cancel original A');
    await toggle(true);
    assert(!input().disabled, 'Reopen must not retain streaming=true');
    assert(document.body.textContent?.includes('first question'), 'History must survive close');
    const b = await send('second question');
    await emit('done', { turnId: b.turnId });
    assert(!input().disabled, 'New turn must terminate normally');
  });
  await test('late old HTTP failure cannot reset new streaming state or overwrite done', async () => {
    const a = await send('A');
    await emit('message', { turnId: a.turnId, content: 'A_FINISHED' });
    await emit('done', { turnId: a.turnId });
    const b = await send('B');
    await emit('progress', { turnId: b.turnId, description: 'B_RUNNING' });
    rejectAsk(a); await settle();
    assert(input().disabled && statusText().includes('B_RUNNING'), 'Old failure must not clear B loading or progress');
    assert(!assistant(a).textContent?.includes('LATE_OLD_HTTP_FAILURE'), 'Completed A must stay completed');
    await emit('done', { turnId: b.turnId });
    assert(!input().disabled, 'B done must still own current state');
  });
  await test('late old HTTP remapping cannot steal the active turn', async () => {
    const a = await send('A'); await emit('done', { turnId: a.turnId });
    const b = await send('B'); ack(a, 'obsolete-remap'); await settle();
    assert(input().disabled, 'B must remain active');
    await emit('done', { turnId: b.turnId });
    assert(!input().disabled, 'B done must not be ignored after late A ack');
  });
  await test('close before acceptance sends another scoped cancellation after late ack', async () => {
    const a = await send('A'); await toggle(false); await toggle(true);
    const b = await send('B'); ack(a); await settle();
    assert(cancellations.filter(item => item.turnId === a.turnId).length >= 2, 'Late acceptance needs another A-only cancellation');
    assert(!cancellations.some(item => item.turnId === b.turnId), 'Late A cancellation must never target B');
    await emit('done', { turnId: a.turnId });
    await emit('progress', { turnId: a.turnId, description: 'STALE_PROGRESS' });
    assert(input().disabled && !statusText().includes('STALE_PROGRESS'), 'Stale events cannot finish or change B');
    await emit('done', { turnId: b.turnId });
  });
  await test('old card receipt updates its card turn without touching new progress', async () => {
    const a = await send('A');
    await emit('interactive_card', { turnId: a.turnId, actionId: 'card-A', cardType: 'TEST', title: 'Card A', description: '', fields: [], confirmButtonText: '确认', cancelButtonText: '放弃' });
    await emit('done', { turnId: a.turnId });
    const b = await send('B'); await emit('progress', { turnId: b.turnId, description: 'B_RUNNING' });
    await emit('message', { turnId: a.turnId, actionId: 'card-A', ticketId: 'T-A', cardReceipt: true, content: 'RECEIPT_A' });
    await emit('error', { turnId: a.turnId, message: 'STALE_ERROR' });
    assert(assistant(a).textContent?.includes('RECEIPT_A'), 'Receipt must remain visible in A');
    assert(!assistant(b).textContent?.includes('RECEIPT_A'), 'Receipt must not enter B');
    assert(input().disabled && statusText().includes('B_RUNNING'), 'Receipt must not clear B progress');
    assert(!assistant(a).textContent?.includes('STALE_ERROR'), 'Late non-receipt error cannot rewrite ended A');
    await emit('done', { turnId: b.turnId });
  });
  await test('transport interruption stops local turn and reconnect allows a new ask', async () => {
    const a = await send('A');
    flushSync(() => Source.latest.onerror?.(new Event('error'))); await settle();
    assert(!input().disabled, 'Transport interruption must clear local generation');
    assert(cancellations.some(item => item.turnId === a.turnId), 'Disconnect must cancel only A');
    Source.latest.open(); await settle(); const b = await send('B');
    ack(a); await settle(); assert(input().disabled, 'Late A ack cannot finish B after reconnect');
    await emit('done', { turnId: b.turnId });
  });
  await test('same-batch double click submits only one turn', async () => {
    enter('double click');
    flushSync(() => { button().click(); button().click(); }); await settle();
    assert(asks.length === 1, 'Same-batch duplicate event must not create two asks');
    await emit('done', { turnId: asks[0].turnId });
  });
  const result = document.getElementById('regression-result')!;
  result.dataset.status = 'passed';
  result.textContent = JSON.stringify({ passed: results.length, tests: results });
}
run().catch(error => {
  const result = document.getElementById('regression-result')!;
  result.dataset.status = 'failed';
  result.textContent = JSON.stringify({ passed: results, failure: String(error?.stack || error) });
});
