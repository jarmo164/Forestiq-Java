"use client";

import Link from "next/link";
import { ExternalLink } from "lucide-react";
import type { OwnerListItem } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const statusLabels: Record<string, string> = { NEW: "Uus", CONTACTED: "Kontakteeritud", INTERESTED: "Huvitatud", NOT_INTERESTED: "Ei huvita", EVALUATION: "Hindamisel", CONTRACTED: "Leping", ASSIGNED: "Määratud", WAITS_FOR_EVALUATION: "Ootab hindamist" };
const statusVariant = (status: string): "secondary" | "outline" | "default" => ["INTERESTED", "CONTRACTED"].includes(status) ? "default" : ["EVALUATION", "WAITS_FOR_EVALUATION", "ASSIGNED"].includes(status) ? "secondary" : "outline";

export function OwnerTable({ data, emptyText = "Omanikke ei leitud." }: { data: OwnerListItem[]; emptyText?: string }) {
  return <Table><TableHeader><TableRow><TableHead>Omanik</TableHead><TableHead>Staatus</TableHead><TableHead>Kinnistuid</TableHead><TableHead>Uuendatud</TableHead><TableHead /></TableRow></TableHeader><TableBody>{data.length ? data.map(owner => <TableRow key={owner.id}><TableCell><p className="font-medium">{owner.displayName}</p><p className="text-xs text-muted-foreground">{owner.personalOrRegistryCode}</p></TableCell><TableCell><Badge variant={statusVariant(owner.status)}>{statusLabels[owner.status] ?? owner.status}</Badge></TableCell><TableCell>{owner.parcelCount}</TableCell><TableCell>{new Intl.DateTimeFormat("et-EE", { dateStyle: "medium" }).format(new Date(owner.updatedAt))}</TableCell><TableCell><Button asChild size="icon" variant="ghost" aria-label="Ava omaniku profiil"><Link href={`/owners/${owner.id}`}><ExternalLink className="h-4 w-4" /></Link></Button></TableCell></TableRow>) : <TableRow><TableCell colSpan={5} className="h-24 text-center text-muted-foreground">{emptyText}</TableCell></TableRow>}</TableBody></Table>;
}
