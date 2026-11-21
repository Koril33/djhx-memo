import { describe, expect, it } from 'vitest';
import { LARGE_NOTE_UTF8_BYTES, isLargeNote, utf8ByteLength } from './note-size';

describe('utf8ByteLength', () => {
  it('counts UTF-8 bytes, not code units', () => {
    expect(utf8ByteLength('abc')).toBe(3);
    expect(utf8ByteLength('中')).toBe(3);
    expect(utf8ByteLength('😀')).toBe(4);
  });
});

describe('isLargeNote threshold', () => {
  it('keeps an ASCII note exactly at the threshold in the normal path', () => {
    const atThreshold = 'a'.repeat(LARGE_NOTE_UTF8_BYTES);
    expect(utf8ByteLength(atThreshold)).toBe(LARGE_NOTE_UTF8_BYTES);
    expect(isLargeNote(atThreshold)).toBe(false);
  });

  it('flags an ASCII note one byte over the threshold', () => {
    const over = 'a'.repeat(LARGE_NOTE_UTF8_BYTES + 1);
    expect(isLargeNote(over)).toBe(true);
  });

  it('uses the exact UTF-8 measure for multi-byte boundary content', () => {
    // 32768 four-byte code points encode to exactly 128 KiB.
    const atThreshold = '😀'.repeat(LARGE_NOTE_UTF8_BYTES / 4);
    expect(utf8ByteLength(atThreshold)).toBe(LARGE_NOTE_UTF8_BYTES);
    expect(isLargeNote(atThreshold)).toBe(false);
    expect(isLargeNote('😀'.repeat(LARGE_NOTE_UTF8_BYTES / 4 + 1))).toBe(true);
  });

  it('returns false for clearly small notes without allocating a UTF-8 buffer', () => {
    expect(isLargeNote('')).toBe(false);
    expect(isLargeNote('short markdown note')).toBe(false);
  });
});
