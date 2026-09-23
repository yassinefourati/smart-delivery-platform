/**
 * Formatting, via Intl and nothing else. The platform charges in USD (payment-service's
 * ChargeRequest defaults to it and products carry no currency of their own).
 */
const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' });
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function formatMoney(amount: number): string {
  return money.format(amount);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '--';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '--' : dateTime.format(d);
}

/** The first eight characters of a UUID: enough to recognise, short enough to read aloud. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** A text form field's trimmed value. FormData can also hold a File, which is never what a text input means. */
export function formText(data: FormData, name: string): string {
  const value = data.get(name);
  return typeof value === 'string' ? value.trim() : '';
}
