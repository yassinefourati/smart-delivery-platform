import { describe, expect, it } from 'vitest';

import { loginUrl, safeNextPath } from '../nextPath';

describe('safeNextPath', () => {
  it.each([
    ['/orders/42', '/orders/42'],
    ['/catalog?search=widget&page=2', '/catalog?search=widget&page=2'],
    [null, '/'],
    ['', '/'],
  ])('%s -> %s', (raw, expected) => {
    expect(safeNextPath(raw)).toBe(expected);
  });

  // Each of these is an open redirect if accepted: a real login page that sends the user
  // somewhere else once they have typed their password.
  it.each([
    'https://evil.example/phish',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    'orders/42',
  ])('refuses %s', (raw) => {
    expect(safeNextPath(raw)).toBe('/');
  });

  it('never sends a user back to the login page, which would be a loop', () => {
    expect(safeNextPath('/login')).toBe('/');
    expect(safeNextPath('/login?next=/orders')).toBe('/');
  });
});

describe('loginUrl', () => {
  it('encodes the destination and the reason', () => {
    expect(loginUrl('/orders/1?x=y', 'expired')).toBe(
      '/login?next=%2Forders%2F1%3Fx%3Dy&reason=expired',
    );
    expect(
      safeNextPath(new URLSearchParams(loginUrl('/orders/1?x=y').split('?')[1]).get('next')),
    ).toBe('/orders/1?x=y');
  });
});
