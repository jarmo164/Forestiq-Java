export type RemovedRow<T> = { item: T; index: number };

export function signedExternalUrlError(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed) return "Sisesta turvaline HTTPS-aadress või laadi allkirjastatud fail.";
  if (/\s/.test(trimmed)) return "Aadress ei tohi sisaldada tühikuid.";
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== "https:") return "Välisviide peab kasutama HTTPS-i.";
    if (!parsed.hostname) return "Välisviitel peab olema kehtiv hostinimi.";
    return undefined;
  } catch {
    return "Sisesta täielik ja kehtiv HTTPS-aadress.";
  }
}

export function removeRowAt<T>(items: T[], index: number): { items: T[]; removed?: RemovedRow<T> } {
  if (items.length <= 1 || index < 0 || index >= items.length) return { items };
  return {
    items: items.filter((_, itemIndex) => itemIndex !== index),
    removed: { item: items[index], index },
  };
}

export function restoreRemovedRow<T>(items: T[], removed: RemovedRow<T>): T[] {
  const index = Math.max(0, Math.min(removed.index, items.length));
  return [...items.slice(0, index), removed.item, ...items.slice(index)];
}
