import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";

type EmptyErrorStateProps = {
  kind: "empty" | "error";
  title: string;
  description?: string;
  onRetry?: () => void;
  action?: ReactNode;
};

export function EmptyErrorState({ kind, title, description, onRetry, action }: EmptyErrorStateProps) {
  return <div role={kind === "error" ? "alert" : "status"} className="rounded-md border bg-muted/30 p-4 text-sm">
    <p className="font-medium">{title}</p>
    {description && <p className="mt-1 text-muted-foreground">{description}</p>}
    {(onRetry || action) && <div className="mt-3 flex flex-wrap gap-2">
      {onRetry && <Button type="button" size="sm" variant="outline" onClick={onRetry}>Proovi uuesti</Button>}
      {action}
    </div>}
  </div>;
}
