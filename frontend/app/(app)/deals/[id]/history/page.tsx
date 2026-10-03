"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  useContactOutcomes,
  useDealWorkflowHistory,
  type HistoryFilters,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const inputClass = "h-10 w-full rounded-md border bg-background px-3 text-sm";

function toIso(localValue: string) {
  if (!localValue) return undefined;
  const parsed = new Date(localValue);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("et-EE", { dateStyle: "medium", timeStyle: "short" });
}

export default function DealHistoryPage() {
  const params = useParams<{ id: string }>();
  const dealId = params.id;
  const { data: outcomes = [] } = useContactOutcomes();
  const [activityType, setActivityType] = useState("");
  const [outcome, setOutcome] = useState("");
  const [author, setAuthor] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [cursorCreatedAt, setCursorCreatedAt] = useState<string | undefined>();
  const [cursorId, setCursorId] = useState<string | undefined>();

  const filters: HistoryFilters = useMemo(() => ({
    activityType: activityType || undefined,
    outcome: outcome || undefined,
    author: author || undefined,
    from: toIso(from),
    to: toIso(to),
    cursorCreatedAt,
    cursorId,
    limit: 50,
  }), [activityType, outcome, author, from, to, cursorCreatedAt, cursorId]);
  const { data: history, isLoading, error } = useDealWorkflowHistory(dealId, filters);

  const resetCursor = () => {
    setCursorCreatedAt(undefined);
    setCursorId(undefined);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Tehingu otsustusajalugu</h1>
          <p className="text-sm text-muted-foreground">Tehing {dealId} · kasutaja- ja automaatsündmused ühes auditeeritavas ajajoones.</p>
        </div>
        <Button asChild variant="outline"><Link href={`/deals/${dealId}/evidence`}>Hindamise tõenduspilt</Link></Button>
      </div>

      <Card>
        <CardContent className="space-y-4 pt-5">
          <div className="grid gap-3 md:grid-cols-5">
            <Input placeholder="Tegevuse tüüp" value={activityType} onChange={event => { setActivityType(event.target.value.toUpperCase()); resetCursor(); }} />
            <select className={inputClass} value={outcome} onChange={event => { setOutcome(event.target.value); resetCursor(); }} aria-label="Kontakti tulemus">
              <option value="">Kõik tulemused</option>
              {outcomes.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
            </select>
            <Input placeholder="Autor / subject" value={author} onChange={event => { setAuthor(event.target.value); resetCursor(); }} />
            <Input type="datetime-local" value={from} onChange={event => { setFrom(event.target.value); resetCursor(); }} aria-label="Alates" />
            <Input type="datetime-local" value={to} onChange={event => { setTo(event.target.value); resetCursor(); }} aria-label="Kuni" />
          </div>

          {isLoading && <p className="text-sm text-muted-foreground">Ajaloo laadimine...</p>}
          {error && <p className="text-sm text-destructive">{error.message}</p>}
          {history?.items.length === 0 && <p className="text-sm text-muted-foreground">Valitud filtritega sündmusi ei ole.</p>}

          <div className="space-y-3">
            {history?.items.map(item => (
              <div key={item.id} className="rounded-lg border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={item.sourceType === "USER" ? "default" : "secondary"}>{item.sourceType === "USER" ? "Kasutaja" : "Automaatne"}</Badge>
                  <Badge variant="outline">{item.activityType}</Badge>
                  {item.channel && <span className="text-sm text-muted-foreground">{item.channel}</span>}
                  {item.outcomeCode && <span className="text-sm font-medium">{outcomes.find(o => o.code === item.outcomeCode)?.label || item.outcomeCode}</span>}
                </div>
                <p className="mt-2 text-sm">{item.content}</p>
                <p className="mt-1 text-xs text-muted-foreground">{formatTime(item.createdAt)} · {item.createdBy}</p>
                {item.relatedActivityId && <p className="mt-1 text-xs text-muted-foreground">Seotud tegevus #{item.relatedActivityId}</p>}
              </div>
            ))}
          </div>

          <div className="flex flex-wrap gap-2">
            {(cursorCreatedAt || cursorId) && <Button variant="outline" size="sm" onClick={resetCursor}>Tagasi esimesele lehele</Button>}
            {history?.nextCursorCreatedAt && history.nextCursorId && (
              <Button variant="outline" size="sm" onClick={() => {
                setCursorCreatedAt(history.nextCursorCreatedAt || undefined);
                setCursorId(history.nextCursorId || undefined);
              }}>Vanemad kirjed</Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
