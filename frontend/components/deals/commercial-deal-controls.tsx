"use client";

import { useMemo, useRef, useState } from "react";
import Link from "next/link";
import { AsyncStatus } from "@/components/ui/async-status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FormField, fieldDescriptionIds } from "@/components/ui/form-field";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/lib/auth";
import {
  type DealLossReason,
  useCancelDeal,
  useDealCommercialState,
  useLoseCommercialDeal,
  usePrepareDealOffer,
  useRecordDealCounterOffer,
  useSendDealOffer,
  useWinDeal,
} from "@/lib/api";

const money = new Intl.NumberFormat("et-EE", { style: "currency", currency: "EUR", maximumFractionDigits: 0 });
const dateTime = new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" });
const TERMINAL = new Set(["WON", "LOST", "CANCELLED"]);
const lossReasonLabels: Record<DealLossReason, string> = {
  PRICE_GAP: "Hinnavahe", CUSTOMER_WITHDREW: "Klient loobus", COMPETITOR: "Konkurent",
  TITLE_OR_LEGAL_RISK: "Õiguslik või omandi risk", PROPERTY_NOT_SUITABLE: "Kinnistu ei sobi",
  NO_RESPONSE: "Klient ei vasta", OTHER: "Muu",
};

type CommercialDealControlsProps = { dealId: string; ownerId: string; onChanged?: () => void };
type FieldErrors = Partial<Record<"offerAmount" | "validUntil" | "counterAmount" | "lossReason" | "closeNote", string>>;

function positiveMoneyError(value: string) {
  if (!value.trim()) return "Sisesta summa.";
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) return "Summa peab olema positiivne lõplik arv.";
  if (!/^\d+(?:[.,]\d{1,2})?$/.test(value.trim())) return "Kasuta euro summat kuni kahe komakohaga.";
  return undefined;
}

export function CommercialDealControls({ dealId, ownerId, onChanged }: CommercialDealControlsProps) {
  const { hasRole } = useAuth();
  const canMutate = hasRole("ORG_ADMIN", "CRM_MANAGER", "CALLER");
  const commercial = useDealCommercialState(dealId);
  const prepare = usePrepareDealOffer(dealId, ownerId);
  const send = useSendDealOffer(dealId, ownerId);
  const counter = useRecordDealCounterOffer(dealId, ownerId);
  const win = useWinDeal(dealId, ownerId);
  const lose = useLoseCommercialDeal(dealId, ownerId);
  const cancel = useCancelDeal(dealId, ownerId);
  const [offerAmount, setOfferAmount] = useState("");
  const [validUntil, setValidUntil] = useState("");
  const [terms, setTerms] = useState("");
  const [counterAmount, setCounterAmount] = useState("");
  const [counterNote, setCounterNote] = useState("");
  const [selectedEntryId, setSelectedEntryId] = useState("");
  const [lossReason, setLossReason] = useState<DealLossReason>("PRICE_GAP");
  const [closeNote, setCloseNote] = useState("");
  const [closeMode, setCloseMode] = useState<"won" | "lost" | "cancelled" | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [successMessage, setSuccessMessage] = useState("");
  const offerAmountRef = useRef<HTMLInputElement>(null);
  const counterAmountRef = useRef<HTMLInputElement>(null);
  const closeNoteRef = useRef<HTMLInputElement>(null);

  const mutationError = useMemo(() => {
    for (const mutation of [prepare, send, counter, win, lose, cancel]) if (mutation.error) return mutation.error.message;
    return null;
  }, [prepare, send, counter, win, lose, cancel]);

  if (commercial.isLoading) return <AsyncStatus state="loading" message="Laadin pakkumise ajalugu…" />;
  if (commercial.isError || !commercial.data) return <AsyncStatus state="error" message={commercial.error?.message ?? "Kommertsandmeid ei saanud laadida."} />;

  const state = commercial.data;
  const offers = state.offers ?? [];
  const latestEntry = offers.at(-1);
  const acceptedEntryId = selectedEntryId || latestEntry?.entryId || "";
  const terminal = TERMINAL.has(state.stage);
  const busy = prepare.isPending || send.isPending || counter.isPending || win.isPending || lose.isPending || cancel.isPending;

  const reportSuccess = (message: string) => { setFieldErrors({}); setSuccessMessage(message); onChanged?.(); };
  const prepareOffer = () => {
    if (busy) return;
    const error = positiveMoneyError(offerAmount);
    if (error) { setFieldErrors({ offerAmount: error }); offerAmountRef.current?.focus(); return; }
    setFieldErrors({}); setSuccessMessage("");
    prepare.mutate({ amount: Number(offerAmount.replace(",", ".")), validUntil: validUntil || undefined, terms: terms || undefined }, { onSuccess: () => {
      setOfferAmount(""); setValidUntil(""); setTerms(""); reportSuccess("Uus pakkumine valmistati ette.");
    }});
  };
  const recordCounter = () => {
    if (busy) return;
    const error = positiveMoneyError(counterAmount);
    if (error) { setFieldErrors({ counterAmount: error }); counterAmountRef.current?.focus(); return; }
    setFieldErrors({}); setSuccessMessage("");
    counter.mutate({ amount: Number(counterAmount.replace(",", ".")), note: counterNote || undefined }, { onSuccess: () => {
      setCounterAmount(""); setCounterNote(""); reportSuccess("Kliendi vastupakkumine salvestati.");
    }});
  };
  const confirmClose = () => {
    if (!closeMode || busy) return;
    if (closeMode === "won" && !acceptedEntryId) { setFieldErrors({ closeNote: "Vali aktsepteeritav pakkumine ajaloost." }); return; }
    if (closeMode === "cancelled" && !closeNote.trim()) { setFieldErrors({ closeNote: "Katkestamise põhjus on kohustuslik." }); closeNoteRef.current?.focus(); return; }
    if (closeMode === "lost" && lossReason === "OTHER" && !closeNote.trim()) { setFieldErrors({ closeNote: "Kirjelda muu kaotuse põhjus." }); closeNoteRef.current?.focus(); return; }
    const done = () => { setCloseMode(null); setCloseNote(""); reportSuccess("Tehingu kommertsstaatus uuendati."); };
    if (closeMode === "won") win.mutate({ acceptedEntryId, note: closeNote || undefined }, { onSuccess: done });
    if (closeMode === "lost") lose.mutate({ lossReason, rejectedEntryId: selectedEntryId || undefined, note: closeNote || undefined }, { onSuccess: done });
    if (closeMode === "cancelled") cancel.mutate({ note: closeNote }, { onSuccess: done });
  };

  return <div className="space-y-4 rounded-md border bg-background p-3">
    <div className="flex flex-wrap items-center justify-between gap-2"><div><p className="font-medium">Pakkumine ja läbirääkimine</p><p className="text-xs text-muted-foreground">Rahaline ajalugu on muutmatu; iga uus pakkumine või vastupakkumine saab uue versiooni.</p></div>{state.closedAt && <Badge variant="secondary">Lõpetatud {dateTime.format(new Date(state.closedAt))}</Badge>}</div>
    {!canMutate && <p className="rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">Pakkumiste ajalugu on vaatamiseks. Kommertstegevused on müügirolli jaoks.</p>}
    {offers.length > 0 ? <div className="space-y-2">{offers.map(offer => { const selected = acceptedEntryId === offer.entryId; const status = offer.acceptedAt ? "Aktsepteeritud" : offer.rejectedAt ? "Tagasi lükatud" : offer.kind === "CUSTOMER_COUNTEROFFER" ? "Kliendi vastupakkumine" : offer.sentAt ? "Saadetud" : "Ettevalmistatud"; return <button type="button" key={offer.entryId} aria-pressed={selected} disabled={!canMutate || terminal || busy} onClick={() => setSelectedEntryId(offer.entryId)} className={`w-full rounded-md border p-3 text-left text-sm ${selected && canMutate && !terminal ? "ring-2 ring-primary" : ""}`}><div className="flex flex-wrap items-center justify-between gap-2"><span className="font-medium">v{offer.revision} · {money.format(offer.amount)}</span><Badge variant={offer.acceptedAt ? "default" : "secondary"}>{status}</Badge></div><p className="mt-1 text-xs text-muted-foreground">{offer.createdBy} · {dateTime.format(new Date(offer.createdAt))}{offer.validUntil ? ` · kehtib ${new Intl.DateTimeFormat("et-EE").format(new Date(offer.validUntil))}` : ""}</p>{offer.terms && <p className="mt-1 text-xs">{offer.terms}</p>}</button>; })}</div> : <p className="text-xs text-muted-foreground">Pakkumisi pole veel loodud.</p>}

    {canMutate && !terminal && (state.stage === "VALUED" || state.stage === "NEGOTIATION") && <fieldset disabled={busy} className="grid gap-2 md:grid-cols-2">
      <FormField id="deal-offer-amount" label="Pakkumise summa (€)" required hint="Positiivne eurosumma, kuni kaks komakohta." error={fieldErrors.offerAmount}><Input ref={offerAmountRef} id="deal-offer-amount" type="number" min="0.01" step="0.01" inputMode="decimal" value={offerAmount} aria-invalid={Boolean(fieldErrors.offerAmount)} aria-describedby={fieldDescriptionIds("deal-offer-amount", "hint", fieldErrors.offerAmount)} onChange={event => setOfferAmount(event.target.value)} /></FormField>
      <FormField id="deal-offer-valid-until" label="Pakkumine kehtib kuni" hint="Valikuline kuupäev."><Input id="deal-offer-valid-until" type="date" value={validUntil} aria-describedby="deal-offer-valid-until-hint" onChange={event => setValidUntil(event.target.value)} /></FormField>
      <FormField id="deal-offer-terms" label="Pakkumise tingimused" hint="Valikuline lisainfo." className="md:col-span-2"><Input id="deal-offer-terms" value={terms} aria-describedby="deal-offer-terms-hint" onChange={event => setTerms(event.target.value)} /></FormField>
      <Button type="button" className="md:col-span-2" onClick={prepareOffer}>Valmista uus pakkumine</Button>
    </fieldset>}

    {canMutate && !terminal && state.stage === "OFFER_PREPARED" && <Button className="w-full" disabled={busy} onClick={() => send.mutate(undefined, { onSuccess: () => reportSuccess("Pakkumine märgiti saadetuks.") })}>Märgi pakkumine saadetuks</Button>}

    {canMutate && !terminal && (state.stage === "OFFER_SENT" || state.stage === "NEGOTIATION") && <fieldset disabled={busy} className="grid gap-2 md:grid-cols-2">
      <FormField id="deal-counter-amount" label="Kliendi vastupakkumine (€)" required hint="Positiivne eurosumma, kuni kaks komakohta." error={fieldErrors.counterAmount}><Input ref={counterAmountRef} id="deal-counter-amount" type="number" min="0.01" step="0.01" inputMode="decimal" value={counterAmount} aria-invalid={Boolean(fieldErrors.counterAmount)} aria-describedby={fieldDescriptionIds("deal-counter-amount", "hint", fieldErrors.counterAmount)} onChange={event => setCounterAmount(event.target.value)} /></FormField>
      <FormField id="deal-counter-note" label="Vastupakkumise märkus" hint="Valikuline läbirääkimise kontekst."><Input id="deal-counter-note" value={counterNote} aria-describedby="deal-counter-note-hint" onChange={event => setCounterNote(event.target.value)} /></FormField>
      <Button type="button" variant="outline" className="md:col-span-2" onClick={recordCounter}>Salvesta kliendi vastupakkumine</Button>
    </fieldset>}

    {canMutate && !terminal && ["VALUED", "OFFER_PREPARED", "OFFER_SENT", "NEGOTIATION"].includes(state.stage) && <div className="space-y-2 border-t pt-3"><div className="flex flex-wrap gap-2">{(state.stage === "OFFER_SENT" || state.stage === "NEGOTIATION") && <Button size="sm" disabled={busy} onClick={() => setCloseMode("won")}>Märgi võidetuks</Button>}<Button size="sm" variant="outline" disabled={busy} onClick={() => setCloseMode("lost")}>Märgi kaotatuks</Button><Button size="sm" variant="ghost" disabled={busy} onClick={() => setCloseMode("cancelled")}>Katkesta</Button></div>
      {closeMode && <fieldset disabled={busy} className="grid gap-2 rounded-md bg-muted/50 p-3">{closeMode === "won" && <p className="text-xs text-muted-foreground">Aktsepteeritav kirje: {acceptedEntryId || "vali pakkumise ajaloost"}</p>}{closeMode === "lost" && <FormField id="deal-loss-reason" label="Kaotuse põhjus" required error={fieldErrors.lossReason}><select id="deal-loss-reason" className="h-10 rounded-md border bg-background px-3 text-sm" value={lossReason} onChange={event => setLossReason(event.target.value as DealLossReason)}>{Object.entries(lossReasonLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></FormField>}<FormField id="deal-close-note" label={closeMode === "cancelled" ? "Katkestamise põhjus" : "Märkus"} required={closeMode === "cancelled" || (closeMode === "lost" && lossReason === "OTHER")} error={fieldErrors.closeNote}><Input ref={closeNoteRef} id="deal-close-note" value={closeNote} aria-invalid={Boolean(fieldErrors.closeNote)} aria-describedby={fieldErrors.closeNote ? "deal-close-note-error" : undefined} onChange={event => setCloseNote(event.target.value)} /></FormField><div className="flex gap-2"><Button size="sm" type="button" onClick={confirmClose}>Kinnita</Button><Button size="sm" type="button" variant="ghost" onClick={() => { setCloseMode(null); setCloseNote(""); setFieldErrors({}); }}>Tühista</Button></div></fieldset>}
    </div>}

    {state.stage === "WON" && <div className="rounded-md border border-emerald-200 bg-emerald-50 p-3"><p className="text-sm font-medium text-emerald-950">Pakkumine on võidetud.</p><p className="mt-1 text-xs text-emerald-900">Koosta leping aktsepteeritud pakkumise ja kinnistute kontrollitud lähteandmete põhjal.</p><Button asChild className="mt-3" size="sm"><Link href={`/contracts?dealId=${encodeURIComponent(dealId)}`}>Koosta leping</Link></Button></div>}
    <AsyncStatus state={busy ? "loading" : mutationError ? "error" : successMessage ? "success" : "idle"} message={busy ? "Salvestan muudatust…" : mutationError ?? successMessage} />
  </div>;
}
