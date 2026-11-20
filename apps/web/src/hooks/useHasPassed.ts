import { useEffect, useState } from 'react';

const MAX_TIMER_DELAY_MS = 2 ** 31 - 1;

/** True once the instant has passed; re-renders at that moment so time-limited actions disappear. */
export function useHasPassed(iso: string): boolean {
  const target = new Date(iso).getTime();
  const [passed, setPassed] = useState(() => target <= Date.now());

  useEffect(() => {
    const remaining = target - Date.now();
    // A longer delay would overflow the timer and fire at once.
    if (remaining <= 0 || remaining > MAX_TIMER_DELAY_MS) return undefined;
    const timer = setTimeout(() => setPassed(true), remaining);
    return () => clearTimeout(timer);
  }, [target]);

  return passed;
}
