"use client";

import Link from "next/link";
import { CalendarClock, RefreshCw } from "lucide-react";
import { useReminderDashboard } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const formatter = new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" });
function startOfDay(date: Date) { return new Date(date.getFullYear(), date.getMonth(), date.getDate()); }
function bucket(date: Date) {
  const today = startOfDay(new Date());
  const tomorrow = new Date(today); tomorrow.setDate(tomorrow.getDate() + 1);
  if (date < today) return { key: "0-overdue", label: "Üle tähtaja", overdue: true };
  if (date < tomorrow) return { key: "1-today", label: "Täna", overdue: false };
  const dayAfterTomorrow = new Date(tomorrow); dayAfterTomorrow.setDate(dayAfterTomorrow.getDate() + 1);
  if (date < dayAfterTomorrow) return { key: "2-tomorrow", label: "Homme", overdue: false };
  return { key: `3-${date.toISOString().slice(0, 10)}`, label: formatter.format(date), overdue: false };
}

export default function RemindersDashboardPage() {
  const { data: reminders, isLoading, isError, error, refetch, isFetching } = useReminderDashboard();
  const grouped = (reminders ?? []).reduce<Record<string, { label: string; overdue: boolean; entries: NonNullable<typeof reminders> }>>((groups, reminder) => {
    const group = bucket(new Date(reminder.dueTime));
    groups[group.key] ??= { label: group.label, overdue: group.overdue, entries: [] };
    groups[group.key].entries.push(reminder);
    return groups;
  }, {});

  return <div className="space-y-6"><div className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="flex items-center gap-2 text-2xl font-bold"><CalendarClock className="h-6 w-6" />Meeldetuletuste juhtpaneel</h1><p className="mt-1 text-sm text-muted-foreground">Üle tähtaja tegevused on alati esimesena; organisatsiooni tähtajad värskendatakse automaatselt iga minuti järel.</p></div><Button variant="outline" onClick={() => void refetch()} disabled={isFetching}><RefreshCw className="mr-2 h-4 w-4" />Värskenda</Button></div>
    {isLoading && <p role="status" className="text-sm text-muted-foreground">Laadimine…</p>}{isError && <div className="space-y-2"><p role="alert" className="text-sm text-destructive">{error.message}</p><Button variant="outline" size="sm" onClick={() => void refetch()}>Proovi uuesti</Button></div>}{reminders && reminders.length === 0 && <Card><CardContent className="p-6 text-sm text-muted-foreground">Organisatsioonis pole aktiivseid meeldetuletusi.</CardContent></Card>}
    {Object.entries(grouped).sort(([left], [right]) => left.localeCompare(right)).map(([, group]) => <Card key={group.label} className={group.overdue ? "border-red-300 bg-red-50/30" : ""}><CardHeader><CardTitle className={group.overdue ? "text-red-900" : ""}>{group.label} {group.overdue && `(${group.entries.length})`}</CardTitle></CardHeader><CardContent><Table><TableHeader><TableRow><TableHead>Tähtaeg</TableHead><TableHead>Tekst</TableHead><TableHead>Omanik</TableHead><TableHead>Looja</TableHead><TableHead>Katastritunnus</TableHead></TableRow></TableHeader><TableBody>{group.entries.map(reminder => <TableRow key={reminder.id} className={group.overdue ? "bg-red-50/40" : ""}><TableCell className={group.overdue ? "font-medium text-red-900" : ""}>{formatter.format(new Date(reminder.dueTime))}</TableCell><TableCell className="font-medium">{reminder.text}</TableCell><TableCell>{reminder.ownerId ? <Link className="text-primary hover:underline" href={`/owners/${reminder.ownerId}`}>{reminder.ownerName ?? "Ava omanik"}</Link> : (reminder.ownerName ?? "—")}</TableCell><TableCell>{reminder.creator}</TableCell><TableCell>{reminder.cadastralCode ?? "—"}</TableCell></TableRow>)}</TableBody></Table></CardContent></Card>)}
  </div>;
}
