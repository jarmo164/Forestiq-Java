import type { ReactNode } from "react";

type FormFieldProps = {
  id: string;
  label: string;
  children: ReactNode;
  hint?: string;
  error?: string;
  required?: boolean;
  className?: string;
};

export function FormField({ id, label, children, hint, error, required, className = "" }: FormFieldProps) {
  return (
    <div className={`grid gap-1 ${className}`}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}{required ? <span aria-hidden="true"> *</span> : null}
      </label>
      {children}
      {hint && <p id={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p id={`${id}-error`} role="alert" className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

export function fieldDescriptionIds(id: string, hint?: string, error?: string) {
  return [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(" ") || undefined;
}
