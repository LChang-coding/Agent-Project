import assert from 'node:assert/strict';
import test from 'node:test';

import { copyText } from '../src/utils/clipboard.ts';

test('secure context prefers Clipboard API', async () => {
  const writes = [];
  await copyText('trace-modern', {
    clipboard: { writeText: async (text) => writes.push(text) },
  });
  assert.deepEqual(writes, ['trace-modern']);
});

test('HTTP context falls back to a temporary textarea', async () => {
  const events = [];
  const textarea = {
    value: '',
    style: {},
    setAttribute: () => {},
    focus: () => events.push('focus'),
    select: () => events.push('select'),
    remove: () => events.push('remove'),
  };
  await copyText('trace-http', {
    document: {
      body: { appendChild: (node) => events.push(`append:${node.value}`) },
      createElement: () => textarea,
      execCommand: (command) => {
        events.push(command);
        return true;
      },
    },
  });
  assert.deepEqual(events, ['append:trace-http', 'focus', 'select', 'copy', 'remove']);
});

test('HTTP fallback restores focus to the invoking control', async () => {
  const events = [];
  const trigger = { focus: () => events.push('restore-trigger') };
  const textarea = {
    value: '',
    style: {},
    setAttribute: () => {},
    focus: () => events.push('focus-textarea'),
    select: () => events.push('select'),
    remove: () => events.push('remove'),
  };

  await copyText('message-body', {
    document: {
      activeElement: trigger,
      body: { appendChild: () => events.push('append') },
      createElement: () => textarea,
      execCommand: () => {
        events.push('copy');
        return true;
      },
    },
  });

  assert.deepEqual(events, ['append', 'focus-textarea', 'select', 'copy', 'remove', 'restore-trigger']);
});

test('Clipboard API rejection also falls back', async () => {
  let fallbackCalled = false;
  await copyText('trace-denied', {
    clipboard: { writeText: async () => { throw new Error('denied'); } },
    document: {
      body: { appendChild: () => {} },
      createElement: () => ({
        value: '', style: {}, setAttribute: () => {}, focus: () => {}, select: () => {}, remove: () => {},
      }),
      execCommand: () => {
        fallbackCalled = true;
        return true;
      },
    },
  });
  assert.equal(fallbackCalled, true);
});
