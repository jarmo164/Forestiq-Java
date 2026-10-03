"use client";

import { useState } from "react";
import Link from "next/link";
import { useInheritanceCases, useInheritanceNotices } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const statuses = ["NOTICE_RECEIVED", "HEIR_DISCOVERY", "HEIRS_CONTACTED", "NEGOTIATION", "SETTLED", "CLOSED", "NOT_RELEVANT"];

export default function InheritanceRegisterPage() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [page, setPage] = useState(0);
  const notices = useInheritanceNotices(100);
  const cases = useInheritanceCases({ query: query || undefined, status: status || undefined, overdue, page, size: 25 });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Pärimisregister</h1>
        <p className="text-sm text-muted-foreground">Ametlike Teadaannete sünkroonitud teated ja omanikega seotud pärimisjuhtumid.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Sünkroonitud pärimisteated</CardTitle>
          <p className="text-sm text-muted-foreground">
            Siin kuvatakse viimased Ametlikest Teadaannetest loetud teated ka siis, kui ForestIQ omanikuga sobitamist ei leitud.
          </p>
        </CardHeader>
        <CardContent className="space-y-4">
          {notices.isLoading && <p className="text-sm text-muted-foreground">Laadin sünkroonitud teateid...</p>}
          {notices.isError && <p className="text-sm text-destructive">Sünkroonitud teadete laadimine ebaõnnestus.</p>}
          {notices.data && (
            <>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>Teade</TableHead><TableHead>Avaldatud</TableHead><TableHead>Surmakuupäev</TableHead>
                  <TableHead>Tähtaeg</TableHead><TableHead>Notar</TableHead><TableHead>Sobitamine</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {notices.data.map(item => (
                    <TableRow key={item.noticeNumber}>
                      <TableCell>
                        <div className="font-medium">{item.noticeNumber}</div>
                        <div className="text-xs text-muted-foreground">Voog: {item.feedDate}</div>
                        {item.sourceUrl && (
                          <a className="text-xs text-primary hover:underline" href={item.sourceUrl} target="_blank" rel="noreferrer">
                            Ava ametlik teade
                          </a>
                        )}
                      </TableCell>
                      <TableCell>{item.announcementDate || "—"}</TableCell>
                      <TableCell>{item.deathDate || "—"}</TableCell>
                      <TableCell>{item.certificationDeadline || "—"}</TableCell>
                      <TableCell>
                        {item.notaryName || "—"}
                        {item.notaryPhone && <div className="text-xs text-muted-foreground">{item.notaryPhone}</div>}
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {item.imported
                            ? "Pärimisasi loodud"
                            : item.ownerMatched
                              ? "Omanik leitud"
                              : item.hasPersonalCode
                                ? "Omanikku ei leitud"
                                : "Isikukood puudub"}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {notices.data.length === 0 && <p className="text-sm text-muted-foreground">Sünkroonitud teateid ei ole.</p>}
              <p className="text-xs text-muted-foreground">
                Kuvatud kuni {notices.data.length} viimast teadet. Pärimisjuhtum luuakse automaatselt ainult siis, kui teate isikukood sobitub organisatsiooni omanikuga.
              </p>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Otsing ja tööjärjekord</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <Input className="max-w-sm" placeholder="Omanik, isikukood või teate number" value={query}
              onChange={event => { setQuery(event.target.value); setPage(0); }} />
            <select className="h-10 rounded-md border bg-background px-3 text-sm" value={status}
              onChange={event => { setStatus(event.target.value); setPage(0); }}>
              <option value="">Kõik staatused</option>
              {statuses.map(value => <option key={value}>{value}</option>)}
            </select>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={overdue} onChange={event => { setOverdue(event.target.checked); setPage(0); }} />
              Ainult tähtaja ületanud
            </label>
          </div>
          {cases.isLoading && <p className="text-sm text-muted-foreground">Laadin pärimisjuhtumeid...</p>}
          {cases.data && (
            <>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>Omanik</TableHead><TableHead>Teade</TableHead><TableHead>Tähtaeg</TableHead>
                  <TableHead>Staatus</TableHead><TableHead>Pärijad</TableHead><TableHead>Määratud</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {cases.data.items.map(item => (
                    <TableRow key={item.id}>
                      <TableCell><Link className="font-medium text-primary hover:underline" href={`/inheritance/${item.id}`}>
                        {item.ownerName}</Link><div className="text-xs text-muted-foreground">{item.ownerCodeMasked}</div></TableCell>
                      <TableCell>{item.noticeNumber || "—"}<div className="text-xs text-muted-foreground">{item.announcementDate || ""}</div></TableCell>
                      <TableCell>{item.certificationDeadline || "—"}</TableCell>
                      <TableCell><Badge variant="outline">{item.status}</Badge></TableCell>
                      <TableCell>{item.contactedHeirCount}/{item.heirCount}</TableCell>
                      <TableCell>{item.assignedToSubject || "Määramata"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {cases.data.items.length === 0 && <p className="text-sm text-muted-foreground">Juhtumeid ei leitud.</p>}
              <div className="flex items-center justify-between text-sm">
                <span>Kokku {cases.data.totalElements}</span>
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(value => value - 1)}>Eelmine</Button>
                  <Button size="sm" variant="outline" disabled={page + 1 >= cases.data.totalPages}
                    onClick={() => setPage(value => value + 1)}>Järgmine</Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

