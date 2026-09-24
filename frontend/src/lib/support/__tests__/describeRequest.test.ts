import endpointsSource from '../../api/endpoints.ts?raw';
import { describeRequest, outcomeOf } from '../describeRequest';

describe('describeRequest', () => {
  it('names every request the app makes in plain words', () => {
    // Every `method: 'X', pathTemplate: '/...'` pair in endpoints.ts, so a new endpoint without
    // a description fails here instead of showing up on the Help page as a raw URL.
    const pairs = [...endpointsSource.matchAll(/method: '(\w+)',\s*pathTemplate: '([^']+)'/g)].map(
      ([, method, path]) => `${method} ${path}`,
    );
    expect(pairs.length).toBeGreaterThan(30);
    const undescribed = pairs.filter((pair) => {
      const [method = '', path = ''] = pair.split(' ');
      return describeRequest(method, path) === pair;
    });
    expect(undescribed).toEqual([]);
  });

  it('falls back to the raw request for anything unknown', () => {
    expect(describeRequest('GET', '/api/v1/unknown')).toBe('GET /api/v1/unknown');
  });
});

describe('outcomeOf', () => {
  it('treats 2xx and 3xx as worked, 0 as no response, and every 4xx or 5xx as a problem', () => {
    expect(outcomeOf(200)).toBe('ok');
    expect(outcomeOf(204)).toBe('ok');
    expect(outcomeOf(0)).toBe('unreachable');
    expect(outcomeOf(409)).toBe('problem');
    expect(outcomeOf(503)).toBe('problem');
  });
});
