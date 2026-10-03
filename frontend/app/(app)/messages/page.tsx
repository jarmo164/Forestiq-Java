"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, CheckCheck, Send } from "lucide-react";
import { useReceivedMessages, useSentMessages, useSendMessage, useMarkMessagesNoticed, useMessageRecipients } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { ErrorNotice } from "./error-notice";

export default function MessagesPage() {
  const [tab, setTab] = useState<"received" | "sent" | "compose">("received");
  const [receivedPage, setReceivedPage] = useState(0);
  const [sentPage, setSentPage] = useState(0);
  const [recipient, setRecipient] = useState("");
  const [messageText, setMessageText] = useState("");
  const [feedback, setFeedback] = useState<string | null>(null);

  const received = useReceivedMessages(receivedPage, 20);
  const sent = useSentMessages(sentPage, 20);
  const recipients = useMessageRecipients();
  const markNoticed = useMarkMessagesNoticed();
  const sendMessage = useSendMessage();

  const handleSend = () => {
    if (!recipient || !messageText.trim()) return;
    setFeedback(null);
    sendMessage.mutate({ recipient, message: messageText }, {
      onSuccess: () => {
        setMessageText("");
        setFeedback("Sõnum saadeti edukalt.");
        setSentPage(0);
        setTab("sent");
      },
    });
  };

  const handleMarkAllRead = () => {
    setFeedback(null);
    markNoticed.mutate(undefined, {
      onSuccess: () => setFeedback("Saadud sõnumid märgiti loetuks."),
    });
  };

  const fmt = (iso: string) => new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold">Sõnumid</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleMarkAllRead} disabled={markNoticed.isPending}>
            <CheckCheck className="mr-2 h-4 w-4" />Märgi kõik loetuks
          </Button>
        </div>
      </div>

      {feedback && <div role="status" className="rounded-md border bg-muted/30 p-3 text-sm">{feedback}</div>}
      {markNoticed.isError && (
        <ErrorNotice message={`Loetuks märkimine ebaõnnestus: ${markNoticed.error.message}`} retryLabel="Proovi uuesti" onRetry={handleMarkAllRead} />
      )}

      <div className="flex gap-2 border-b pb-2">
        <Button variant={tab === "received" ? "default" : "ghost"} size="sm" onClick={() => setTab("received")}>Saadud</Button>
        <Button variant={tab === "sent" ? "default" : "ghost"} size="sm" onClick={() => setTab("sent")}>Saadetud</Button>
        <Button variant={tab === "compose" ? "default" : "ghost"} size="sm" onClick={() => setTab("compose")}>Uus sõnum</Button>
      </div>

      {tab === "compose" && (
        <Card>
          <CardHeader><CardTitle>Saada sõnum</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="grid gap-1">
                <label className="text-sm font-medium">Saaja</label>
                {recipients.isPending && <p className="text-sm text-muted-foreground">Laadin saajaid...</p>}
                {recipients.isError && (
                  <ErrorNotice message={`Saajate laadimine ebaõnnestus: ${recipients.error.message}`} retryLabel="Laadi uuesti" onRetry={() => recipients.refetch()} />
                )}
                {recipients.data && recipients.data.length > 0 ? (
                  <select className="h-10 rounded-md border bg-background px-3 text-sm" value={recipient} onChange={e => setRecipient(e.target.value)}>
                    <option value="">Vali saaja...</option>
                    {recipients.data.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                  </select>
                ) : !recipients.isPending && !recipients.isError ? (
                  <Input placeholder="Kasutajanimi" value={recipient} onChange={e => setRecipient(e.target.value)} />
                ) : null}
              </div>
            </div>
            <div className="grid gap-1">
              <label className="text-sm font-medium">Sõnum</label>
              <textarea className="min-h-[120px] w-full rounded-md border bg-background p-3 text-sm" value={messageText} onChange={e => setMessageText(e.target.value)} placeholder="Kirjuta sõnum..." />
            </div>
            <Button onClick={handleSend} disabled={!recipient || !messageText.trim() || sendMessage.isPending}>
              <Send className="mr-2 h-4 w-4" />Saada
            </Button>
            {sendMessage.isError && (
              <ErrorNotice message={`Sõnumi saatmine ebaõnnestus: ${sendMessage.error.message}`} retryLabel="Proovi uuesti" onRetry={handleSend} />
            )}
          </CardContent>
        </Card>
      )}

      {tab === "received" && (
        <Card>
          <CardContent className="pt-4">
            {received.isLoading && <p className="text-sm text-muted-foreground">Laadimine...</p>}
            {received.isError && (
              <ErrorNotice message={`Saadud sõnumite laadimine ebaõnnestus: ${received.error.message}`} retryLabel="Laadi uuesti" onRetry={() => received.refetch()} />
            )}
            {received.data && (
              <Table>
                <TableHeader>
                  <TableRow><TableHead>Saatja</TableHead><TableHead>Sõnum</TableHead><TableHead>Kuupäev</TableHead><TableHead>Loetud</TableHead></TableRow>
                </TableHeader>
                <TableBody>
                  {received.data.map(m => (
                    <TableRow key={m.id} className={!m.noticedAt ? "bg-blue-50" : ""}>
                      <TableCell className="font-medium">{m.sender}</TableCell>
                      <TableCell>
                        <div className="flex flex-wrap items-center gap-2">
                          <span>{m.message}</span>
                          {m.sender === "system-reminder" && <Button asChild variant="outline" size="sm"><Link href="/my-work">Ava töövaade<ArrowRight className="ml-1 h-3.5 w-3.5" /></Link></Button>}
                        </div>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{fmt(m.createdAt)}</TableCell>
                      <TableCell>{m.noticedAt ? <Badge variant="outline">Loetud</Badge> : <Badge>Uus</Badge>}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {received.data?.length === 0 && <p className="text-sm text-muted-foreground">Saadud sõnumeid pole.</p>}
            {received.data && <div className="mt-4 flex items-center justify-between gap-3 text-sm text-muted-foreground"><span>Leht {receivedPage + 1} · kuvatud {received.data.length} sõnumit</span><div className="flex gap-2"><Button size="sm" variant="outline" disabled={receivedPage === 0 || received.isFetching} onClick={() => setReceivedPage(current => current - 1)}>Uuemad</Button><Button size="sm" variant="outline" disabled={received.data.length < 20 || received.isFetching} onClick={() => setReceivedPage(current => current + 1)}>Vanemad</Button></div></div>}
          </CardContent>
        </Card>
      )}

      {tab === "sent" && (
        <Card>
          <CardContent className="pt-4">
            {sent.isLoading && <p className="text-sm text-muted-foreground">Laadimine...</p>}
            {sent.isError && (
              <ErrorNotice message={`Saadetud sõnumite laadimine ebaõnnestus: ${sent.error.message}`} retryLabel="Laadi uuesti" onRetry={() => sent.refetch()} />
            )}
            {sent.data && (
              <Table>
                <TableHeader>
                  <TableRow><TableHead>Saaja</TableHead><TableHead>Sõnum</TableHead><TableHead>Kuupäev</TableHead></TableRow>
                </TableHeader>
                <TableBody>
                  {sent.data.map(m => (
                    <TableRow key={m.id}>
                      <TableCell className="font-medium">{m.recipient}</TableCell>
                      <TableCell>{m.message}</TableCell>
                      <TableCell className="text-muted-foreground">{fmt(m.createdAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {sent.data?.length === 0 && <p className="text-sm text-muted-foreground">Saadetud sõnumeid pole.</p>}
            {sent.data && <div className="mt-4 flex items-center justify-between gap-3 text-sm text-muted-foreground"><span>Leht {sentPage + 1} · kuvatud {sent.data.length} sõnumit</span><div className="flex gap-2"><Button size="sm" variant="outline" disabled={sentPage === 0 || sent.isFetching} onClick={() => setSentPage(current => current - 1)}>Uuemad</Button><Button size="sm" variant="outline" disabled={sent.data.length < 20 || sent.isFetching} onClick={() => setSentPage(current => current + 1)}>Vanemad</Button></div></div>}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
