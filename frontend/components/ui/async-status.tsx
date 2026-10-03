type AsyncStatusProps = {
  state: "idle" | "loading" | "success" | "error";
  message?: string | null;
  className?: string;
};

export function AsyncStatus({ state, message, className = "" }: AsyncStatusProps) {
  if (state === "idle" || !message) return null;
  if (state === "error") {
    return <p role="alert" className={`text-sm text-destructive ${className}`}>{message}</p>;
  }
  return <p role="status" aria-live="polite" className={`text-sm text-muted-foreground ${className}`}>{message}</p>;
}
