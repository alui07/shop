const WINDOW_SIZE = 5;

/** Page numbers (1-based) to show around the current page, at most WINDOW_SIZE of them. */
export function pageWindow(current: number, totalPages: number): number[] {
  const size = Math.min(WINDOW_SIZE, totalPages);
  const start = Math.max(1, Math.min(current - Math.floor(size / 2), totalPages - size + 1));
  return Array.from({ length: size }, (_unused, index) => start + index);
}
