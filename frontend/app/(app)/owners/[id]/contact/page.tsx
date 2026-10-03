"use client";

import { FormEvent, useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  useContactOutcomes,
  useCreateContact,
  useOwner,
  useOwnerDeals,
  useOwnerOpenActivities,
  useOwnerWorkflowHistory,
  useWorkflowAssignees,
  type HistoryFilters,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const inputClass = "h-10 w-full rounded-md border bg-background px-3 text-sm";
const textareaClass = "min-h-24 w-full rounded-md border bg-background px-3 py-2 text-sm";

function toIso(localValue: string) {
  if (!localValue) return undefined;
  const parsed = new Date(localValue);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("et-EE", { dateStyle: "medium", timeStyle: "short" });
}

export default function OwnerContactPage() {
  const params = useParams<{ id: string }>();
  const ownerId = params.id;
  const { data: owner } = useOwner(ownerId);
  const { data: deals = [] } = useOwnerDeals(ownerId);
  const { data: outcomes = [] } = useContactOutcomes();
  const { data: assignees = [] } = useWorkflowAssignees();
  const { data: openActivities = [] } = useOwnerOpenActivities(ownerId);
  const createContact = useCreateContact(ownerId);

  const [channel, setChannel] = useState<"PHONE" | "EMAIL" | "MEETING" | "OTHER">("PHONE");
  const [outcomeCode, setOutcomeCode] = useState("");
  const [reason, setReason] = useState("");
  const [dealId, setDealId] = useState("");
  const [activityMode, setActivityMode] = useState<"NONE" | "EXISTING" | "NEW">("NONE");
  const [existingActivityId, setExistingActivityId] = useState("");
  const [nextStep, setNextStep] = useState("");
  const [nextStepDueAt, setNextStepDueAt] = useState("");
  const [assigneeSubject, setAssigneeSubject] = useState("");
  const [activityType, setActivityType] = useState("FOLLOW_UP");
  const [priorityReason, setPriorityReason] = useState("");
  const [savedMessage, setSavedMessage] = useState("");

  const [activityTypeFilter, setActivityTypeFilter] = useState("");
  const [outcomeFilter, setOutcomeFilter] = useState("");
  const [authorFilter, setAuthorFilter] = useState("");
  const [fromFilter, setFromFilter] = useState("");
  const [toFilter, setToFilter] = useState("");
  const [cursorCreatedAt, setCursorCreatedAt] = useState<string | undefined>();
  const [cursorId, setCursorId] = useState<string | undefined>();

  const selectedOutcome = outcomes.find(item => item.code === outcomeCode);
  const requiresNextActivity = Boolean(selectedOutcome?.requiresNextActivity);
  const activeOutcomes = outcomes.filter(item => item.active);
  const historyFilters: HistoryFilters = useMemo(() => ({
    activityType: activityTypeFilter || undefined,
    outcome: outcomeFilter || undefined,
    author: authorFilter || undefined,
    from: toIso(fromFilter),
    to: toIso(toFilter),
    cursorCreatedAt,
    cursorId,
    limit: 30,
  }), [activityTypeFilter, outcomeFilter, authorFilter, fromFilter, toFilter, cursorCreatedAt, cursorId]);
  const { data: history, isLoading: historyLoading } = useOwnerWorkflowHistory(ownerId, historyFilters);

  const resetCursor = () => {
    setCursorCreatedAt(undefined);
    setCursorId(undefined);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSavedMessage("");
    const due = toIso(nextStepDueAt);
    if (requiresNextActivity && activityMode === "NONE") {
      setSavedMessage("Selle tulemuse puhul on järgmine tegevus kohustuslik.");
      return;
    }
    if (requiresNextActivity && activityMode === "EXISTING" && !existingActivityId) {
      setSavedMessage("Vali olemasolev tegevus või loo uus järeltegevus.");
      return;
    }
    if (requiresNextActivity && activityMode === "NEW" && !nextStep.trim()) {
      setSavedMessage("Selle tulemuse puhul on järgmise tegevuse kirjeldus kohustuslik.");
      return;
    }
    if (activityMode === "NEW" && !due) {
      setSavedMessage("Uue tegevuse tähtaeg ei ole korrektne.");
      return;
    }
    createContact.mutate({
      channel,
      outcomeCode,
      reason: reason.trim() || undefined,
      dealId: dealId || undefined,
      existingActivityId: activityMode === "EXISTING" && existingActivityId ? Number(existingActivityId) : undefined,
      nextStep: activityMode === "NEW" ? nextStep.trim() : undefined,
      nextStepDueAt: activityMode === "NEW" ? due : undefined,
      assigneeSubject: activityMode === "NEW" ? assigneeSubject || undefined : undefined,
      activityType: activityMode === "NEW" ? activityType : undefined,
      priorityReason: activityMode === "NEW" ? priorityReason.trim() || undefined : undefined,
    }, {
      onSuccess: result => {
        setSavedMessage(result.activityId ? `Kontakt salvestatud ja seotud tegevusega #${result.activityId}.` : "Kontakt salvestatud.");
        setReason("");
        setActivityMode("NONE");
        setExistingActivityId("");
        setNextStep("");
        setNextStepDueAt("");
        setAssigneeSubject("");
        setPriorityReason("");
        resetCursor();
      },
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Salvesta kontakt</h1>
          <p className="text-sm text-muted-foreground">{owner?.displayName || "Omanik"} · struktureeritud kontakt ja järgmine tegevus.</p>
        </div>
        <Button variant="outline" asChild><Link href={`/owners/${ownerId}`}>Tagasi omaniku juurde</Link></Button>
      </div>

      <Card>
        <CardContent className="pt-5">
          <form className="space-y-5" onSubmit={submit}>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="space-y-1 text-sm font-medium">
                Kanal *
                <select className={inputClass} value={channel} onChange={event => setChannel(event.target.value as typeof channel)} required>
                  <option value="PHONE">Telefon</option>
                  <option value="EMAIL">E-post</option>
                  <option value="MEETING">Kohtumine</option>
                  <option value="OTHER">Muu</option>
                </select>
              </label>
              <label className="space-y-1 text-sm font-medium">
                Tulemus *
                <select className={inputClass} value={outcomeCode} onChange={event => {
                  const code = event.target.value;
                  setOutcomeCode(code);
                  if (outcomes.find(item => item.code === code)?.requiresNextActivity) setActivityMode("NEW");
                }} required>
                  <option value="">Vali tulemus</option>
                  {activeOutcomes.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
                </select>
              </label>
            </div>

            <label className="block space-y-1 text-sm font-medium">
              Põhjendus {selectedOutcome?.requiresReason ? "*" : ""}
              <textarea
                className={textareaClass}
                value={reason}
                onChange={event => setReason(event.target.value)}
                required={selectedOutcome?.requiresReason}
                maxLength={2000}
                placeholder={selectedOutcome?.requiresReason ? "Selle tulemuse puhul on põhjendus kohustuslik." : "Lisa vajadusel kontakti kokkuvõte või põhjendus."}
              />
            </label>

            <label className="block space-y-1 text-sm font-medium">
              Seotud tehing
              <select className={inputClass} value={dealId} onChange={event => setDealId(event.target.value)}>
                <option value="">Ilma tehinguseoseta</option>
                {deals.map(deal => (
                  <option key={deal.id} value={deal.id}>{deal.saleSubject} · {deal.stage} · {deal.id.slice(0, 8)}</option>
                ))}
              </select>
            </label>

            <div className="space-y-3 rounded-lg border p-4">
              <div><p className="font-medium">Järgmine tegevus {requiresNextActivity ? "*" : ""}</p>{requiresNextActivity && <p className="mt-1 text-sm text-amber-800">Valitud tulemuse tööreegel nõuab järgmist tegevust koos tähtaja ja vastutajaga.</p>}</div>
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant={activityMode === "NONE" ? "default" : "outline"} onClick={() => setActivityMode("NONE")} disabled={requiresNextActivity}>Ei lisa</Button>
                <Button type="button" size="sm" variant={activityMode === "EXISTING" ? "default" : "outline"} onClick={() => setActivityMode("EXISTING")}>Seo olemasolevaga</Button>
                <Button type="button" size="sm" variant={activityMode === "NEW" ? "default" : "outline"} onClick={() => setActivityMode("NEW")}>Loo uus</Button>
              </div>

              {activityMode === "EXISTING" && (
                <label className="block space-y-1 text-sm font-medium">
                  Avatud tegevus *
                  <select className={inputClass} value={existingActivityId} onChange={event => setExistingActivityId(event.target.value)} required>
                    <option value="">Vali tegevus</option>
                    {openActivities.map(item => (
                      <option key={item.id} value={item.id}>#{item.id} · {item.text} · {formatTime(item.dueTime)}</option>
                    ))}
                  </select>
                </label>
              )}

              {activityMode === "NEW" && (
                <div className="grid gap-4 md:grid-cols-2">
                  <label className="space-y-1 text-sm font-medium md:col-span-2">
                    Tegevus *
                    <Input value={nextStep} onChange={event => setNextStep(event.target.value)} required maxLength={500} placeholder="Näiteks: helista tagasi ja küsi hinnasoovi" />
                  </label>
                  <label className="space-y-1 text-sm font-medium">
                    Tähtaeg *
                    <Input type="datetime-local" value={nextStepDueAt} onChange={event => setNextStepDueAt(event.target.value)} required />
                  </label>
                  <label className="space-y-1 text-sm font-medium">
                    Vastutaja
                    <select className={inputClass} value={assigneeSubject} onChange={event => setAssigneeSubject(event.target.value)}>
                      <option value="">Mina ise</option>
                      {assignees.map(user => <option key={user.subject} value={user.subject}>{user.name || user.username}</option>)}
                    </select>
                  </label>
                  <label className="space-y-1 text-sm font-medium">
                    Tegevuse tüüp
                    <select className={inputClass} value={activityType} onChange={event => setActivityType(event.target.value)}>
                      <option value="FOLLOW_UP">Järeltegevus</option>
                      <option value="CALL">Kõne</option>
                      <option value="EMAIL">E-post</option>
                      <option value="MEETING">Kohtumine</option>
                    </select>
                  </label>
                  <label className="space-y-1 text-sm font-medium">
                    Prioriteedi põhjus
                    <Input value={priorityReason} onChange={event => setPriorityReason(event.target.value)} maxLength={300} placeholder="Näiteks: pakkumine aegub" />
                  </label>
                </div>
              )}
            </div>

            {(savedMessage || createContact.error) && (
              <p className={`text-sm ${createContact.error ? "text-destructive" : "text-muted-foreground"}`}>
                {createContact.error ? createContact.error.message : savedMessage}
              </p>
            )}
            <Button type="submit" disabled={createContact.isPending}>{createContact.isPending ? "Salvestan..." : "Salvesta kontakt"}</Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-4 pt-5">
          <div>
            <h2 className="text-lg font-semibold">Otsustusajalugu</h2>
            <p className="text-sm text-muted-foreground">Filtreeri kasutaja tehtud ja automaatseid sündmusi. Tulemused on järjestatud uusimast vanimani.</p>
          </div>

          <div className="grid gap-3 md:grid-cols-5">
            <select className={inputClass} value={activityTypeFilter} onChange={event => { setActivityTypeFilter(event.target.value); resetCursor(); }} aria-label="Tegevuse tüüp">
              <option value="">Kõik tüübid</option>
              <option value="PHONE">Telefon</option>
              <option value="EMAIL">E-post</option>
              <option value="MEETING">Kohtumine</option>
              <option value="REMINDER">Tegevused</option>
              <option value="STATUS_CHANGE">Staatuse muutus</option>
            </select>
            <select className={inputClass} value={outcomeFilter} onChange={event => { setOutcomeFilter(event.target.value); resetCursor(); }} aria-label="Kontakti tulemus">
              <option value="">Kõik tulemused</option>
              {outcomes.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
            </select>
            <Input placeholder="Autor / subject" value={authorFilter} onChange={event => { setAuthorFilter(event.target.value); resetCursor(); }} />
            <Input type="datetime-local" value={fromFilter} onChange={event => { setFromFilter(event.target.value); resetCursor(); }} aria-label="Alates" />
            <Input type="datetime-local" value={toFilter} onChange={event => { setToFilter(event.target.value); resetCursor(); }} aria-label="Kuni" />
          </div>

          {historyLoading && <p className="text-sm text-muted-foreground">Ajaloo laadimine...</p>}
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
