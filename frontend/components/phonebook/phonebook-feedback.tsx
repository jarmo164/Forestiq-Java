"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { Trash2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { canDeletePhonebookEntry } from "@/lib/phonebook-permissions";

export function PhonebookDeleteAction({
  roles,
  entryId,
  entryName,
  pending,
  onConfirm,
}: {
  roles: readonly string[];
  entryId: number;
  entryName?: string | null;
  pending: boolean;
  onConfirm: (entryId: number) => void;
}) {
  if (!canDeletePhonebookEntry(roles)) return null;

  const label = entryName || `#${entryId}`;
  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <Button variant="ghost" size="sm" aria-label={`Kustuta kontakt ${label}`}>
          <Trash2 className="h-4 w-4 text-destructive" />
        </Button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border bg-background p-6 shadow-lg">
          <div className="flex items-start justify-between gap-4">
            <Dialog.Title className="text-lg font-semibold">Kustuta kontakt?</Dialog.Title>
            <Dialog.Close asChild>
              <Button variant="ghost" size="sm" aria-label="Sulge kustutamise dialoog"><X className="h-4 w-4" /></Button>
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-2 text-sm text-muted-foreground">
            Kontakt {label} eemaldatakse organisatsiooni kontaktiraamatust. Seda toimingut ei saa tagasi võtta.
          </Dialog.Description>
          <div className="mt-5 flex justify-end gap-2">
            <Dialog.Close asChild><Button variant="ghost" disabled={pending}>Tühista</Button></Dialog.Close>
            <Dialog.Close asChild>
              <Button disabled={pending} onClick={() => onConfirm(entryId)}>Kustuta</Button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function PhonebookErrorNotice({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
      <span>{message}</span>
      <Button variant="ghost" size="sm" className="ml-2" onClick={onRetry}>Proovi uuesti</Button>
    </div>
  );
}
