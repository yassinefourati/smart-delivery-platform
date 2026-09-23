import { orderStatusValues, type OrderStatusView } from '../../../lib/api/schemas/order';
import { MILESTONES, STATUS_META, stepState } from '../statusMeta';

const ALL: OrderStatusView[] = [...orderStatusValues, 'UNKNOWN'];

describe('statusMeta', () => {
  it('covers all ten wire statuses plus UNKNOWN', () => {
    expect(Object.keys(STATUS_META).sort()).toEqual([...ALL].sort());
  });

  it.each([
    ['INVENTORY_RESERVATION_PENDING', 'Reserving stock'],
    ['PAYMENT_PENDING', 'Taking payment'],
  ] as const)('%s never renders its own milestone as done', (status, milestone) => {
    const index = MILESTONES.indexOf(milestone);
    expect(stepState(status, index)).toBe('active');
  });

  it('never shows a later milestone as done than the saga has reached', () => {
    expect(stepState('CREATED', 1)).toBe('active');
    expect(stepState('INVENTORY_RESERVED', 2)).toBe('active');
    expect(stepState('PAID', 2)).toBe('done');
    expect(stepState('SHIPMENT_CREATED', 4)).toBe('upcoming');
  });

  it('marks every milestone done only when DELIVERED', () => {
    for (const status of ALL) {
      const allDone = MILESTONES.every((_, i) => stepState(status, i) === 'done');
      expect(allDone).toBe(status === 'DELIVERED');
    }
  });

  it('gives the terminal failures no step at all, so they render a panel instead of a stepper', () => {
    for (const status of ['CANCELLED', 'FAILED', 'UNKNOWN'] as const) {
      expect(STATUS_META[status].active).toBe(-1);
    }
  });
});
