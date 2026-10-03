"use client";

import { Button } from "@/components/ui/button";

export function ErrorNotice({ message, retryLabel, onRetry }: { message: string; retryLabel: string; onRetry: () => void }) {
  return (
    <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
      <span>{message}</span>
      <Button variant="ghost" size="sm" className="ml-2" onClick={onRetry}>{retryLabel}</Button>
    </div>
  );
}
