import { describe, expect, it } from 'vitest';
import { pageWindow } from './pageWindow';

describe('pageWindow', () => {
  it.each([
    [1, 1, [1]],
    [1, 3, [1, 2, 3]],
    [1, 10, [1, 2, 3, 4, 5]],
    [3, 10, [1, 2, 3, 4, 5]],
    [5, 10, [3, 4, 5, 6, 7]],
    [10, 10, [6, 7, 8, 9, 10]],
    [9, 10, [6, 7, 8, 9, 10]],
  ])('page %i of %i', (current, total, expected) => {
    expect(pageWindow(current, total)).toEqual(expected);
  });
});
