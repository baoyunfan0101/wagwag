const MAX_PRICE_CENTS = 100_000_000;

export function parseListingPrice(value: string): number {
  const match = /^\$?(0|[1-9]\d{0,6})(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) throw new Error('Enter a USD price with at most two decimal places. Use 0 for free.');
  const cents = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
  if (cents > MAX_PRICE_CENTS) throw new Error('Price must be at most $1,000,000.');
  return cents;
}

export function formatListingPrice(cents: number): string {
  return cents === 0 ? 'Free' : `$${(cents / 100).toFixed(2)}`;
}
