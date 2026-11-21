/**
 * Shared large-note threshold for the desktop display path.
 *
 * The 0.2.0 M2 plan (D1) fixes 128 KiB UTF-8 as the starting threshold, matching
 * the core shared code-highlight upper bound. Above this size the desktop skips
 * optional decorations (source code spans and the preview render) but keeps the
 * body, save cadence and find count/navigation intact.
 */
export const LARGE_NOTE_UTF8_BYTES = 128 * 1024;

/** Exact UTF-8 byte length of a string. */
export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

/**
 * Whether a note body exceeds the large-note threshold.
 *
 * Uses a cheap UTF-16 code-unit bound first so the common large (mostly ASCII)
 * and small cases never allocate a UTF-8 buffer; only the narrow boundary range
 * is measured exactly.
 */
export function isLargeNote(value: string): boolean {
  const { length } = value;
  // UTF-8 is at most 3 bytes per code unit for BMP text and 4 bytes per surrogate
  // pair (2 units), so 3 * length is a safe upper bound.
  if (length * 3 < LARGE_NOTE_UTF8_BYTES) return false;
  // UTF-8 is at least 1 byte per code unit.
  if (length > LARGE_NOTE_UTF8_BYTES) return true;
  return utf8ByteLength(value) > LARGE_NOTE_UTF8_BYTES;
}
