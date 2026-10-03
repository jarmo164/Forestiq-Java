import { Button } from "@/components/ui/button";

type UndoToastProps = {
  message: string;
  onUndo: () => void;
  onDismiss?: () => void;
};

export function UndoToast({ message, onUndo, onDismiss }: UndoToastProps) {
  return <div role="status" aria-live="polite" className="flex flex-wrap items-center gap-2 rounded-md border bg-background p-3 text-sm shadow-sm">
    <span className="flex-1">{message}</span>
    <Button type="button" size="sm" variant="outline" onClick={onUndo}>Võta tagasi</Button>
    {onDismiss && <Button type="button" size="sm" variant="ghost" onClick={onDismiss}>Sulge</Button>}
  </div>;
}
