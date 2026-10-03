"use client";

import { useEffect, useId, useRef } from "react";
import { Button } from "@/components/ui/button";

type ConfirmActionDialogProps = {
  open: boolean;
  title: string;
  description: React.ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  destructive?: boolean;
  pending?: boolean;
  confirmDisabled?: boolean;
  error?: string;
  onConfirm: () => void;
  onCancel: () => void;
  children?: React.ReactNode;
};

/** Accessible, small-footprint confirmation primitive for high-impact mutations. */
export function ConfirmActionDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = "Tühista",
  destructive = false,
  pending = false,
  confirmDisabled = false,
  error,
  onConfirm,
  onCancel,
  children,
}: ConfirmActionDialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    cancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !pending) onCancel();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      previous?.focus();
    };
  }, [onCancel, open, pending]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !pending) onCancel();
    }}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        className="w-full max-w-lg rounded-lg border bg-white p-5 shadow-xl"
      >
        <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
        <div id={descriptionId} className="mt-2 text-sm text-muted-foreground">{description}</div>
        {children && <div className="mt-4">{children}</div>}
        {error && <p role="alert" className="mt-3 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">{error}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button ref={cancelRef} type="button" variant="outline" onClick={onCancel} disabled={pending}>{cancelLabel}</Button>
          <Button type="button" variant={destructive ? "destructive" : "default"} onClick={onConfirm} disabled={pending || confirmDisabled} aria-busy={pending}>
            {pending ? "Salvestan…" : confirmLabel}
          </Button>
        </div>
      </section>
    </div>
  );
}
