"use client";

import { RefreshCw, Route } from "lucide-react";
import { useOwnershipTransitionEvents, useOwnershipTransitionSync } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useAuth } from "@/lib/auth";

function formatDate(value?: string) {
  return value ? new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—";
}

const DIRECTION_LABELS: Record<string, string> = {
  OUTGOING: "Senise omaniku väljumine",
  INCOMING: "Uue omaniku lisandumine",
  MIXED: "Omanike koosseisu muutus",
  UNKNOWN: "Omanike koosseisu muutus",
};

/** Displays only the minimal operational audit retained by ForestIQ. */
export function OwnershipTransitionPanel({ ownerId }: { ownerId: string }) {
  const { hasRole } = useAuth();
  const events = useOwnershipTransitionEvents(ownerId);
  const sync = useOwnershipTransitionSync();
  if (!hasRole("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR")) return null;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle className="flex items-center gap-2 text-base"><Route className="h-4 w-4 text-primary" />Omandimuutused</CardTitle>
          <p className="mt-1 text-sm text-muted-foreground">Weaseli katastri omandiüleminekute andmeallika auditijälg.</p>
        </div>
        {hasRole("ORG_ADMIN", "CRM_MANAGER") && <Button size="sm" variant="outline" disabled={sync.isPending} onClick={() => sync.mutate()}><RefreshCw className={"mr-1 h-3 w-3 " + (sync.isPending ? "animate-spin" : "")} />Sünkroniseeri</Button>}
      </CardHeader>
      <CardContent className="space-y-3">
        {sync.data && <p className={"rounded-md border p-2 text-xs " + ((sync.data.failed ?? 0) > 0 ? "border-amber-400 bg-amber-50" : "border-emerald-400 bg-emerald-50")}>{sync.data.message} Sobitatud: {sync.data.matched ?? 0}, uued: {sync.data.imported ?? 0}.</p>}
        {events.isLoading && <p className="text-sm text-muted-foreground">Laen omandimuutuste andmeid…</p>}
        {events.isError && <p className="text-sm text-destructive">Omandimuutuste andmed ei ole praegu saadaval.</p>}
        {events.data?.length === 0 && <p className="text-sm text-muted-foreground">Selle omanikuga seotud Weaseli omandimuutusi pole veel tuvastatud.</p>}
        {events.data?.map(event => <div className="border-l-2 border-primary/30 pl-3" key={event.weaselTransitionId}>
          <div className="flex flex-wrap items-center gap-2"><p className="text-sm font-medium">Katastriüksus {event.cadastralCode}</p><Badge variant="secondary">Weasel #{event.weaselTransitionId}</Badge></div>
          <p className="mt-1 text-xs text-muted-foreground">{event.relationshipDirection ? DIRECTION_LABELS[event.relationshipDirection] ?? DIRECTION_LABELS.UNKNOWN : DIRECTION_LABELS.UNKNOWN} · tuvastatud {formatDate(event.transitionAt)} · enne {event.oldOwnerCount ?? 0} omanikku, pärast {event.newOwnerCount ?? 0} omanikku.</p>
        </div>)}
      </CardContent>
    </Card>
  );
}
