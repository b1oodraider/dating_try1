// Per-account localStorage entries (UI preferences only, never secrets).
// Every access is wrapped: storage can be unavailable (private mode, blocked site data) or full.

const PREFIX = 'dating_mvp_acc';

/** "dating_mvp_acc:<name>:<accountId>" */
export function accountStorageKey(name: string, accountId: string): string {
  return `${PREFIX}:${name}:${accountId}`;
}

/** Parsed JSON stored for the account, or null when missing, unreadable or corrupt. */
export function readAccountJson(name: string, accountId: string): unknown {
  try {
    const raw = localStorage.getItem(accountStorageKey(name, accountId));
    return raw === null ? null : (JSON.parse(raw) as unknown);
  } catch {
    return null;
  }
}

export function writeAccountJson(name: string, accountId: string, value: unknown): void {
  try {
    localStorage.setItem(accountStorageKey(name, accountId), JSON.stringify(value));
  } catch {
    // Storage unavailable or full: the value simply won't survive a reload.
  }
}

/** Removes every per-account entry of this account (logout, account deletion). */
export function clearAccountStorage(accountId: string): void {
  try {
    const suffix = `:${accountId}`;
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(`${PREFIX}:`) && key.endsWith(suffix)) keys.push(key);
    }
    keys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // Storage unavailable: nothing to clear.
  }
}
