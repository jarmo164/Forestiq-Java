import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Returns a conservative tel URI without changing the CRM value shown to a user. */
export function toTelHref(phone?: string | null) {
  if (!phone) return undefined;
  const compact = phone.trim().replace(/[\s().-]/g, "");
  if (!/^\+?\d{5,15}$/.test(compact)) return undefined;
  return `tel:${compact}`;
}
