import assert from 'node:assert/strict';
import test from 'node:test';
import { formatListingPrice, parseListingPrice } from './listingPrice.ts';

test('USD listing prices use integer cents and allow free items', () => {
  assert.equal(parseListingPrice('0'), 0);
  assert.equal(parseListingPrice('12'), 1200);
  assert.equal(parseListingPrice('$12.3'), 1230);
  assert.equal(parseListingPrice(' 12.34 '), 1234);
  assert.equal(parseListingPrice('1000000'), 100000000);
  assert.equal(formatListingPrice(0), 'Free');
  assert.equal(formatListingPrice(1234), '$12.34');
});

test('listing prices reject invalid precision and values above the API limit', () => {
  for (const value of ['', '-1', '1.234', '1e2', '01', '1000000.01', '10000000', 'NaN']) {
    assert.throws(() => parseListingPrice(value), value);
  }
});
