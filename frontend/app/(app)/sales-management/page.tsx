"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRightLeft, BarChart3, BellRing, ClipboardCheck, RefreshCw, UserRoundCheck, UsersRound } from "lucide-react";
import { useSalesManagementOverview } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const STATUS_LABELS: Record<string, string> = {
  NEW: "Uus", CONTACTED: "Kontakteeritud", INTERESTED: "Huvitatud", EVALUATION: "Hindamisel",
  WAITS_FOR_EVALUATION: "Ootab hindamist", ASSIGNED: "Määratud", CONTRACTED: "Leping", NOT_INTERESTED: "Ei huvita",
};

const INTERVENTION_COPY: Record<string, { title: string; detail: string }> = {
  UNASSIGNED_LEAD: { title: "Määramata müügivihje", detail: "Määra omanik vastutajale, et esimene kontakt ei hilineks." },
  STALE_OPPORTUNITY: { title: "Vananenud müügivõimalus", detail: "Kontrolli järgmist sammu või lepi uus kontakt kokku." },
};
const DEFAULT_INTERVENTION_COPY = { title: "Sekkumist vajav töö", detail: "Kontrolli omanikukaardilt järgmine vajalik samm." };

const timeFormatter = new Intl.DateTimeFormat("et-EE", { dateStyle: "short", timeStyle: "short" });

export default function SalesManagementPage() {
  const overview = useSalesManagementOverview();
  const data = overview.data;
  const team = data?.team ?? [];
  const interventions = data?.interventions ?? [];
  const averageAssigned = team.length ? (data?.assignedOwners ?? 0) / team.length : 0;

  const metrics = [
    { label: "Määramata vihjed", value: data?.unassignedLeads, icon: UsersRound, detail: "Vajavad omanikku ja esimest kontakti", critical: (data?.unassignedLeads ?? 0) > 0 },
    { label: "Vananenud võimalused", value: data?.staleOpportunities, icon: AlertTriangle, detail: "Kontakteeritud või huvitatud, kuid 7 päeva uuenduseta", critical: (data?.staleOpportunities ?? 0) > 0 },
    { label: "Tähtaja ületanud", value: data?.overdueReminders, icon: BellRing, detail: "Organisatsiooni tasandil lahendamist ootavad kokkulepped", critical: (data?.overdueReminders ?? 0) > 0 },
    { label: "Tegevusi 7 päeva", value: data?.salesActivitiesLast7Days, icon: BarChart3, detail: "Märkmed ja töövoo sündmused meeskonnas", critical: false },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold tracking-tight"><UserRoundCheck className="h-7 w-7" />Müügimeeskonna juhtimine</h1>
          <p className="mt-2 text-muted-foreground">Juhi töölaud näitab, kus tuleb täna määrata töö, aidata müüjat või taastada kontakt tehinguga.</p>
        </div>
        <div className="flex gap-2"><Button asChild variant="outline"><Link href="/admin"><ArrowRightLeft className="mr-2 h-4 w-4" />Tööjaotus</Link></Button><Button asChild><Link href="/map"><ClipboardCheck className="mr-2 h-4 w-4" />Kaarditööruum</Link></Button></div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {metrics.map(({ label, value, icon: Icon, detail, critical }) => <Card key={label} className={critical ? "border-amber-300 bg-amber-50/40" : ""}><CardHeader className="flex-row items-center justify-between space-y-0 pb-2"><CardTitle className="text-sm font-medium">{label}</CardTitle><Icon className={critical ? "h-4 w-4 text-amber-700" : "h-4 w-4 text-primary"} /></CardHeader><CardContent><div className="text-2xl font-bold">{value?.toString() ?? "—"}</div><p className="mt-1 text-xs text-muted-foreground">{detail}</p></CardContent></Card>)}
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.35fr_1fr]">
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0"><CardTitle>Meeskonna töökoormus ja edenemine</CardTitle><span className="text-xs text-muted-foreground">Uueneb iga minut</span></CardHeader>
          <CardContent>
            {overview.isLoading && <p className="text-sm text-muted-foreground">Laadin meeskonna juhtimisandmeid...</p>}
            {overview.isError && <p className="text-sm text-destructive">{overview.error.message}</p>}
            {data && team.length === 0 && <p className="text-sm text-muted-foreground">Meeskonna tööandmed tekivad, kui kasutajatele määratakse omanikke või lisatakse tegevusi.</p>}
            {team.length > 0 && <Table><TableHeader><TableRow><TableHead>Meeskonnaliige</TableHead><TableHead>Määratud</TableHead><TableHead>Aktiivsed võimalused</TableHead><TableHead>Staatusemuudatusi 7 p</TableHead><TableHead>Tähtaja ületus</TableHead><TableHead>Viimane tegevus</TableHead></TableRow></TableHeader><TableBody>{team.map(member => {
              const assignedOwners = member.assignedOwners ?? 0;
              const overdueReminders = member.overdueReminders ?? 0;
              const overloaded = averageAssigned > 0 && assignedOwners > averageAssigned * 1.5;
              return <TableRow key={member.subject}><TableCell className="font-medium">{member.subject}</TableCell><TableCell><Badge variant={overloaded ? "default" : "outline"}>{assignedOwners}{overloaded ? " · kõrge koormus" : ""}</Badge></TableCell><TableCell>{member.activeOpportunities ?? 0}</TableCell><TableCell>{member.statusChangesLast7Days ?? 0}</TableCell><TableCell>{overdueReminders > 0 ? <Badge variant="warning">{overdueReminders}</Badge> : "—"}</TableCell><TableCell>{member.lastActivityAt ? timeFormatter.format(new Date(member.lastActivityAt)) : "Tegevus puudub"}</TableCell></TableRow>;
            })}</TableBody></Table>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0"><CardTitle>Juhi sekkumisjärjekord</CardTitle>{overview.isFetching && <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />}</CardHeader>
          <CardContent className="space-y-3">
            {data && interventions.length === 0 && <p className="text-sm text-muted-foreground">Praegu ei paista määramata ega vananenud müügivõimalusi. Hoia meeldetuletuste tähtaegadel siiski silm peal.</p>}
            {interventions.map(item => {
              const copy = item.reason ? INTERVENTION_COPY[item.reason] ?? DEFAULT_INTERVENTION_COPY : DEFAULT_INTERVENTION_COPY;
              return <div key={`${item.reason}-${item.ownerId}`} className="rounded-md border p-3"><div className="flex items-start justify-between gap-3"><div><Badge variant={item.reason === "UNASSIGNED_LEAD" ? "warning" : "outline"}>{copy.title}</Badge><p className="mt-2 text-sm font-semibold">{item.ownerName}</p><p className="text-xs text-muted-foreground">{item.status ? STATUS_LABELS[item.status] ?? item.status : "—"} · {item.assignedTo ? `Vastutaja: ${item.assignedTo}` : "Vastutaja puudub"}</p></div><Button asChild size="sm" variant="outline"><Link href={`/owners/${item.ownerId}`}>Ava</Link></Button></div><p className="mt-2 text-xs text-muted-foreground">{copy.detail}{item.updatedAt ? ` Viimane muutus: ${timeFormatter.format(new Date(item.updatedAt))}.` : ""}</p></div>;
            })}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
