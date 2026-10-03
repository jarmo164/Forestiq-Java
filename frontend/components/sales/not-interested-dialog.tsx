"use client";

import type { SalesTask } from "@/lib/types";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";

type NotInterestedDialogProps = {
  task: SalesTask | null;
  reason: string;
  pending: boolean;
  error?: string;
  onReasonChange: (value: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
};

export function NotInterestedDialog({ task, reason, pending, error, onReasonChange, onConfirm, onCancel }: NotInterestedDialogProps) {
  return <ConfirmActionDialog
    open={Boolean(task)}
    title="Kas lõpetada aktiivne müügisiht?"
    description={task ? <>Tulemus <strong>„Ei huvita”</strong> eemaldab omaniku <strong>{task.ownerName}</strong> aktiivsest müügijärjekorrast. Lisa põhjendus, et järgmine kasutaja näeks otsuse konteksti omaniku ajaloos.</> : ""}
    confirmLabel="Salvesta „Ei huvita”"
    destructive
    pending={pending}
    confirmDisabled={!reason.trim()}
    error={error}
    onConfirm={onConfirm}
    onCancel={onCancel}
  >
    <label className="grid gap-1 text-sm font-medium" htmlFor="not-interested-reason">
      Põhjendus *
      <textarea id="not-interested-reason" className="min-h-24 rounded-md border bg-background p-3 text-sm" value={reason} onChange={(event) => onReasonChange(event.target.value)} maxLength={2000} placeholder="Näiteks: omanik ei soovi praegu pakkumist arutada." />
    </label>
  </ConfirmActionDialog>;
}
