"use client";

import { useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatIsoDate, freshnessLabel } from "@/lib/map/parcel-panel-format";

export function DataField({ label, children }: { label: string; children: ReactNode }) {
  return <div><p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p><div className="mt-0.5 text-sm">{children}</div></div>;
}

export function DataSection({
  title,
  count,
  defaultOpen = false,
  onOpen,
  children,
}: {
  title: string;
  count?: number;
  defaultOpen?: boolean;
  onOpen?: () => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <details
      open={open}
      onToggle={event => {
        const next = event.currentTarget.open;
        setOpen(next);
        if (next) onOpen?.();
      }}
      className="group rounded-lg border bg-slate-50/70"
    >
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-3 text-sm font-semibold marker:hidden">
        <span className="flex items-center gap-2"><span aria-hidden="true" className="text-muted-foreground transition-transform group-open:rotate-90">›</span>{title}</span>
        {count !== undefined && <Badge variant="outline">{count}</Badge>}
      </summary>
      <div className="border-t px-3 pb-3 pt-3">{children}</div>
    </details>
  );
}

export function QueryMessage({
  loading,
  error,
  empty,
  emptyText,
  retry,
}: {
  loading: boolean;
  error: boolean;
  empty: boolean;
  emptyText: string;
  retry?: () => void;
}) {
  if (loading) return <p className="text-sm text-muted-foreground" role="status">Laadin...</p>;
  if (error) return <div className="flex items-center justify-between gap-2 rounded-md border border-amber-200 bg-amber-50 p-2 text-sm text-amber-900" role="alert"><span>Andmete laadimine ebaõnnestus. Ülejäänud katastriinfo töötab edasi.</span>{retry && <Button size="sm" variant="outline" onClick={retry}>Proovi uuesti</Button>}</div>;
  if (empty) return <p className="text-sm text-muted-foreground">{emptyText}</p>;
  return null;
}

export function SourceMeta({
  source,
  sourceDate,
  generationId,
  freshness,
}: {
  source: string;
  sourceDate?: string | null;
  generationId?: string | null;
  freshness?: string | null;
}) {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t pt-2 text-[11px] text-muted-foreground">
      <span>{source}</span>
      {sourceDate && <span>· andmeseis {formatIsoDate(sourceDate)}</span>}
      {generationId && <span title={generationId}>· gen {generationId.slice(0, 8)}</span>}
      {freshness && <Badge variant="outline" className="h-5 px-1.5 text-[10px]">{freshnessLabel(freshness)}</Badge>}
    </div>
  );
}
