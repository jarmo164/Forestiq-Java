"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { usePhonebook, useCreatePhonebookEntry, useDeletePhonebookEntry } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { PhonebookDeleteAction, PhonebookErrorNotice } from "@/components/phonebook/phonebook-feedback";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export default function PhonebookPage() {
  const [searchName, setSearchName] = useState("");
  const [searchPhone, setSearchPhone] = useState("");
  const [searchCode, setSearchCode] = useState("");
  const [showAdd, setShowAdd] = useState(false);
  const [newName, setNewName] = useState("");
  const [newPhone, setNewPhone] = useState("");
  const [newAddress, setNewAddress] = useState("");
  const [newCode, setNewCode] = useState("");

  const { roles } = useAuth();
  const entriesQuery = usePhonebook(searchName, searchPhone, searchCode);
  const createEntry = useCreatePhonebookEntry();
  const deleteEntry = useDeletePhonebookEntry();

  const handleCreate = () => {
    if (!newName && !newPhone) return;
    createEntry.mutate({ name: newName || undefined, phone: newPhone || undefined, address: newAddress || undefined, code: newCode || undefined }, {
      onSuccess: () => {
        setNewName("");
        setNewPhone("");
        setNewAddress("");
        setNewCode("");
        setShowAdd(false);
      },
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Kontaktiraamat</h1>
          <p className="text-sm text-muted-foreground">Organisatsiooni kontaktide andmebaas.</p>
        </div>
        <Button onClick={() => setShowAdd(!showAdd)}><Plus className="mr-2 h-4 w-4" />Lisa kontakt</Button>
      </div>

      {showAdd && (
        <Card>
          <CardHeader><CardTitle>Uus kontakt</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 md:grid-cols-4">
              <Input placeholder="Nimi" value={newName} onChange={e => setNewName(e.target.value)} />
              <Input placeholder="Telefon" value={newPhone} onChange={e => setNewPhone(e.target.value)} />
              <Input placeholder="Aadress" value={newAddress} onChange={e => setNewAddress(e.target.value)} />
              <Input placeholder="Kood" value={newCode} onChange={e => setNewCode(e.target.value)} />
            </div>
            <div className="flex gap-2">
              <Button onClick={handleCreate} disabled={(!newName && !newPhone) || createEntry.isPending}>Salvesta</Button>
              <Button variant="ghost" onClick={() => setShowAdd(false)}>Tühista</Button>
            </div>
            {createEntry.isError && (
              <PhonebookErrorNotice message={`Kontakti lisamine ebaõnnestus: ${createEntry.error.message}`} onRetry={handleCreate} />
            )}
          </CardContent>
        </Card>
      )}

      <div className="flex flex-wrap gap-3">
        <Input placeholder="Otsi nime järgi" value={searchName} onChange={e => setSearchName(e.target.value)} className="w-48" />
        <Input placeholder="Otsi telefoni järgi" value={searchPhone} onChange={e => setSearchPhone(e.target.value)} className="w-48" />
        <Input placeholder="Otsi koodi järgi" value={searchCode} onChange={e => setSearchCode(e.target.value)} className="w-48" />
      </div>

      <Card>
        <CardContent className="pt-4">
          {entriesQuery.isLoading && <p className="text-sm text-muted-foreground">Laadimine...</p>}
          {entriesQuery.isError && (
            <PhonebookErrorNotice message={`Kontaktide laadimine ebaõnnestus: ${entriesQuery.error.message}`} onRetry={() => void entriesQuery.refetch()} />
          )}
          {entriesQuery.data && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Nimi</TableHead>
                  <TableHead>Telefon</TableHead>
                  <TableHead>Aadress</TableHead>
                  <TableHead>Kood</TableHead>
                  <TableHead>Allikas</TableHead>
                  <TableHead>Toimingud</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entriesQuery.data.map(entry => (
                  <TableRow key={entry.id}>
                    <TableCell className="font-medium">{entry.name || "—"}</TableCell>
                    <TableCell>{entry.phone || "—"}</TableCell>
                    <TableCell>{entry.address || "—"}</TableCell>
                    <TableCell>{entry.code || "—"}</TableCell>
                    <TableCell className="text-muted-foreground">{entry.source || "—"}</TableCell>
                    <TableCell>
                      <PhonebookDeleteAction
                        roles={roles}
                        entryId={entry.id}
                        entryName={entry.name}
                        pending={deleteEntry.isPending}
                        onConfirm={entryId => deleteEntry.mutate(entryId)}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {entriesQuery.data?.length === 0 && <p className="text-sm text-muted-foreground">Kontakte ei leitud.</p>}
          {deleteEntry.isError && deleteEntry.variables != null && (
            <div className="mt-3">
              <PhonebookErrorNotice
                message={`Kontakti kustutamine ebaõnnestus: ${deleteEntry.error.message}`}
                onRetry={() => deleteEntry.mutate(deleteEntry.variables!)}
              />
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
