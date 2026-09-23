import { useQuery } from '@tanstack/react-query';

import { getInventorySummary } from '../../lib/api/endpoints';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';

/**
 * Live stock for one product. `GET /api/v1/inventory/:productId` is permitAll, so this works
 * signed out.
 *
 * ADVISORY, NOT AUTHORITATIVE: the saga's reservation is the real check. But showing it -- and
 * blocking checkout when a line asks for more than exists -- prevents the most common FAILED
 * order there is: one created against stock that was already zero, which fails four seconds
 * later for a reason the customer cannot see.
 *
 * `throwOnError: false` (the default here) so a failed lookup degrades to "stock unknown" and
 * never blanks the page that asked.
 */
export function useStock(productId: string) {
  return useQuery({
    queryKey: queryKeys.inventory.summary(productId),
    queryFn: ({ signal }) => getInventorySummary(productId, { signal }),
    staleTime: STALE_TIME.INVENTORY,
  });
}
