"use client";

import { useState } from "react";
import { BriefcaseBusiness, CalendarClock, ChevronDown, ChevronUp, Plus } from "lucide-react";
import { useCallBrief } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { CommercialDealControls } from "@/components/deals/commercial-deal-controls";
import { DealContractLink } from "@/components/deals/deal-contract-link";
import { DealQualificationDialog } from "@/components/deals/deal-qualification-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const STAGE_LABELS: Record<string, string> = {
  QUALIFIED: "Kvalifitseeritud",
  EVALUATION_REQUESTED: "Hindamine tellitud",
  EVALUATING: "Hindamisel",
  VALUED: "Hinnastatud",
  OFFER_PREPARED: "Pakkumine ette valmistatud",
  OFFER_SENT: "Pakkumine saadetud",
  NEGOTIATION: "Läbirääkimised",
  WON: "Võidetud",
  LOST: "Kaotatud",
  CANCELLED: "Katkestatud",
};

const subjectLabels: Record<string, string> = { FOREST: "Mets", LAND: "Maa", BOTH: "Mets ja maa" };
const dateTime = new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" });
const money = new Intl.NumberFormat("et-EE", { style: "currency", currency: "EUR", maximumFractionDigits: 0 });
const COMMERCIAL_STAGES = new Set(["VALUED", "OFFER_PREPARED", "OFFER_SENT", "NEGOTIATION", "WON", "LOST", "CANCELLED"]);

type DealCasePanelProps = {
  ownerId: string;
  ownerStatus: string;
  onOwnerWorkflowChanged?: () => void;
};

export function DealCasePanel({ ownerId, ownerStatus, onOwnerWorkflowChanged }: DealCasePanelProps) {
  const { hasRole } = useAuth();
  const brief = useCallBrief(ownerId);
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const canCreateDeal = hasRole("ORG_ADMIN", "CRM_MANAGER", "CALLER");
  const canCreate = canCreateDeal && ownerStatus !== "DECEASED" && ownerStatus !== "CONTRACTED";

  if (brief.isLoading) {
    return <Card><CardContent className="p-4 text-sm text-muted-foreground">Laadin tehingujuhtumeid…</CardContent></Card>;
  }
  if (brief.isError) {
    return <Card><CardContent className="p-4 text-sm text-destructive">Tehingujuhtumite laadimine ebaõnnestus.</CardContent></Card>;
  }

  const deals = brief.data?.deals ?? [];
  return <Card>
    <CardHeader className="flex-row items-center justify-between gap-3">
      <div>
        <CardTitle className="flex items-center gap-2"><BriefcaseBusiness className="h-5 w-5" />Tehingujuhtumid</CardTitle>
        <p className="mt-1 text-xs text-muted-foreground">Huvi, hindamine, pakkumised, läbirääkimised ja lõpetamine ühes auditeeritavas ajaloos.</p>
      </div>
      {canCreate && <Button size="sm" onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />Kvalifitseeri huvi</Button>}
    </CardHeader>
    <CardContent className="space-y-3">
      {open && <DealQualificationDialog ownerId={ownerId} open onCancel={() => setOpen(false)} onCreated={() => { setOpen(false); onOwnerWorkflowChanged?.(); }} />}
      {!deals.length && !open && <p className="text-sm text-muted-foreground">Aktiivset tehingujuhtumit ei ole. {canCreate ? "Kvalifitseeri huvi kohe pärast sisukat kõnet, et süsteem saaks hindaja üleandmise ja järgmise sammu ise hallata." : "Uue tehingujuhtumi loomine on müügirolli tegevus."}</p>}

      {deals.map(deal => <div className="rounded-md border p-3" key={deal.id}>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <Badge>{STAGE_LABELS[deal.stage] ?? deal.stage}</Badge>
              <span className="text-sm font-medium">{subjectLabels[deal.saleSubject] ?? deal.saleSubject}</span>
            </div>
            <p className="mt-2 text-sm">Järgmine samm: <strong>{deal.nextAction}</strong></p>
            {deal.nextActionDueAt && <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
              <CalendarClock className="h-3.5 w-3.5" />{dateTime.format(new Date(deal.nextActionDueAt))}
            </p>}
          </div>
          <Button size="sm" variant="ghost" onClick={() => setExpanded(expanded === deal.id ? null : deal.id)}>
            {expanded === deal.id ? <><ChevronUp className="mr-1 h-4 w-4" />Peida</> : <><ChevronDown className="mr-1 h-4 w-4" />Detailid</>}
          </Button>
        </div>

        {expanded === deal.id && <div className="mt-3 grid gap-3 border-t pt-3 text-sm md:grid-cols-2">
          <p><span className="text-muted-foreground">Kinnistud:</span> {deal.parcels.map(parcel => `${parcel.cadastralCode} (${parcel.areaHectares.toFixed(2)} ha)`).join(", ")}</p>
          <p><span className="text-muted-foreground">Ajaraam:</span> {deal.saleTimeframe ?? "—"}</p>
          <p><span className="text-muted-foreground">Otsustaja:</span> {deal.decisionMaker ?? "—"}</p>
          <p><span className="text-muted-foreground">Kliendi hinnasoov:</span> {deal.priceExpectation ? money.format(deal.priceExpectation) : "—"}</p>
          <p className="md:col-span-2"><span className="text-muted-foreground">Kõne kokkuvõte:</span> {deal.qualificationNotes ?? "—"}</p>

          {deal.latestEvaluation && <div className="rounded-md bg-muted/50 p-3 md:col-span-2">
            <p className="font-medium">Viimane hindamisotsus · versioon {deal.latestEvaluation.revision}</p>
            <p className="mt-1">Soovituslik ostuhind: {deal.latestEvaluation.recommendedPurchasePrice ? money.format(deal.latestEvaluation.recommendedPurchasePrice) : "—"}</p>
            {deal.latestEvaluation.risks && <p className="mt-1 text-muted-foreground">Riskid: {deal.latestEvaluation.risks}</p>}
          </div>}

          {COMMERCIAL_STAGES.has(deal.stage) && <div className="md:col-span-2">
            <CommercialDealControls dealId={deal.id} ownerId={ownerId} onChanged={onOwnerWorkflowChanged} />
          </div>}

          {deal.stage === "WON" && <div className="md:col-span-2"><DealContractLink dealId={deal.id} /></div>}

          <div className="md:col-span-2">
            <p className="mb-2 font-medium">Tegevusajalugu</p>
            <div className="space-y-2">
              {deal.events.map(event => <div className="border-l-2 border-primary/30 pl-3" key={event.id}>
                <p>{event.description}</p>
                <p className="text-xs text-muted-foreground">{event.createdBy} · {dateTime.format(new Date(event.createdAt))}</p>
              </div>)}
            </div>
          </div>
        </div>}
      </div>)}
    </CardContent>
  </Card>;
}
