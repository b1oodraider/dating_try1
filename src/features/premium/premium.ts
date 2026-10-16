import type { Wallet } from '../../core/types';

/**
 * Premium-only features the account cannot use right now: listed in Wallet.premiumFeatures while premium
 * is inactive, plus features the server refused with 403 PREMIUM_REQUIRED (e.g. the wallet is stale).
 * Unknown wallet (still loading / failed) locks nothing: the server stays the source of truth.
 */
export function lockedFeaturesOf(wallet: Wallet | null, refusedByServer: readonly string[]): Set<string> {
  const locked = new Set(refusedByServer);
  if (wallet && !wallet.premium.active) {
    for (const feature of wallet.premiumFeatures) locked.add(feature);
  }
  return locked;
}
