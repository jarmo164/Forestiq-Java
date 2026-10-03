export type RequiredProperties<T, K extends keyof T> = T & {
  [P in K]-?: NonNullable<T[P]>;
};

export function requireProperties<T extends object, K extends keyof T>(
  value: T,
  keys: readonly K[],
  context: string,
): RequiredProperties<T, K> {
  const record = value as unknown as Record<PropertyKey, unknown>;
  for (const key of keys) {
    if (record[key as PropertyKey] === undefined || record[key as PropertyKey] === null) {
      throw new Error(`${context} is missing required field "${String(key)}".`);
    }
  }
  return value as RequiredProperties<T, K>;
}
