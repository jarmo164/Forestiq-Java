"use client";

import Link from "next/link";
import { BellRing, BriefcaseBusiness, ClipboardCheck, FileCheck2, MapPinned, PhoneCall, Sprout, UsersRound } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useDashboardStats, useReminders } from "@/lib/api";

export default function DashboardPage() {
  const { data: stats } = useDashboardStats();
  const { data: reminders } = useReminders();
  const now = new Date();
  const upcomingReminders = reminders?.filter(reminder => new Date(reminder.dueTime) > now).sort((a, b) => new Date(a.dueTime).getTime() - new Date(b.dueTime).getTime()).slice(0, 5);

  const metrics = [
    { label: "Uued müügivihjed", value: stats?.newLeads, icon: UsersRound, detail: "Omanikud, kes ootavad esimest kontakti", href: "/map" },
    { label: "Aktiivsed võimalused", value: stats?.activeOpportunities, icon: PhoneCall, detail: "Kontakteeritud või huvitatud omanikud", href: "/owners" },
    { label: "Hindamist ootavad", value: stats?.pendingEvaluation, icon: ClipboardCheck, detail: "Järgmine samm enne pakkumist", href: "/evaluation-workdesk" },
    { label: "Määratud töö", value: stats?.assignedWork, icon: BriefcaseBusiness, detail: "Helistajatele määratud tegevused", href: "/my-work" },
    { label: "Sõlmitud lepingud", value: stats?.contracted, icon: FileCheck2, detail: "Lepingustaatuses omanikud", href: "/contracts" },
    { label: "Kaardil kinnistuid", value: stats?.totalParcels, icon: MapPinned, detail: "Kiireks ruumiandmete kontrolliks", href: "/map" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Müügi ja töövoo ülevaade</h1>
          <p className="mt-2 text-muted-foreground">Juhi järgmised prioriteedid ning kiireim tee omanikust järgmise tegevuseni.</p>
        </div>
        <Link className="inline-flex items-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90" href="/map"><MapPinned className="mr-2 h-4 w-4" />Ava kaarditööruum</Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {metrics.map(({ label, value, icon: Icon, detail, href }) => (
          <Link key={label} href={href} className="group">
            <Card className="h-full transition-shadow group-hover:shadow-md">
              <CardHeader className="flex-row items-center justify-between space-y-0 pb-2"><CardTitle className="text-sm font-medium">{label}</CardTitle><Icon className="h-4 w-4 text-primary" /></CardHeader>
              <CardContent><div className="text-2xl font-bold">{value?.toString() ?? "—"}</div><p className="mt-1 text-xs text-muted-foreground">{detail}</p></CardContent>
            </Card>
          </Link>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0"><CardTitle>Minu järgmised tähtajad</CardTitle>{(stats?.overdueReminders ?? 0) > 0 && <span className="rounded-full bg-red-100 px-2 py-1 text-xs font-medium text-red-700">{stats?.overdueReminders} tähtaja ületanud organisatsiooni tasandil</span>}</CardHeader>
          <CardContent>
            {upcomingReminders && upcomingReminders.length > 0 ? <div className="space-y-3">{upcomingReminders.map(reminder => <div key={reminder.id} className="border-l-2 border-amber-400 pl-3"><p className="text-sm font-medium">{reminder.text}</p><p className="text-xs text-muted-foreground">{new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(reminder.dueTime))}{reminder.ownerName && ` · ${reminder.ownerName}`}</p></div>)}</div> : <p className="text-sm text-muted-foreground">Lähimaid meeldetuletusi pole.</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Soovitatud tööjärjekord</CardTitle></CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Link href="/map" className="flex items-start gap-3 rounded-md p-2 hover:bg-slate-50"><Sprout className="mt-0.5 h-4 w-4 text-primary" /><span><strong>Kaardilt kinnistuni.</strong><br /><span className="text-muted-foreground">Otsi omanik või katastritunnus, vali polügoon ja ava kohe töökaart või telefonikõne.</span></span></Link>
            <Link href="/evaluation-workdesk" className="flex items-start gap-3 rounded-md p-2 hover:bg-slate-50"><ClipboardCheck className="mt-0.5 h-4 w-4 text-primary" /><span><strong>Liiguta hindamisjärjekord edasi.</strong><br /><span className="text-muted-foreground">Hindamisel olevad omanikud on järgmine samm enne pakkumise või lepingu loomist.</span></span></Link>
            <Link href="/reminders" className="flex items-start gap-3 rounded-md p-2 hover:bg-slate-50"><BellRing className="mt-0.5 h-4 w-4 text-primary" /><span><strong>Hoia kontaktid tähtajalised.</strong><br /><span className="text-muted-foreground">Tee meeldetuletus igale kokkulepitud järeltegevusele, et tehinguvõimalus ei jahtuks.</span></span></Link>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
