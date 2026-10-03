"use client";

import { Suspense, useMemo } from "react";
import { useSearchParams } from "next/navigation";
import { Trees } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { safeInternalPath } from "@/lib/workspace-routes";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const loginErrors: Record<string, string> = {
  state: "Sisselogimise turvakontroll aegus või ei klappinud. Alusta sisselogimist uuesti.",
  service: "Sisselogimisteenus ei ole praegu saadaval. Proovi mõne hetke pärast uuesti.",
};

function LoginPageContent() {
  const searchParams = useSearchParams();
  const { authenticated, login } = useAuth();
  const returnTo = useMemo(() => safeInternalPath(searchParams.get("returnTo")), [searchParams]);
  const error = loginErrors[searchParams.get("error") ?? ""];
  const loginHref = `/auth/login${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`;

  if (authenticated) {
    return <main role="status" className="grid min-h-screen place-items-center text-sm text-muted-foreground">Avan sinu töölauda…</main>;
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[radial-gradient(circle_at_top,_#e6f4ea,_#f8fafc_45%)] p-6">
      <Card className="w-full max-w-md">
        <CardHeader className="items-center text-center"><div className="mb-2 rounded-full bg-secondary p-3 text-primary"><Trees className="h-8 w-8" /></div><CardTitle className="text-2xl">Tere tulemast ForestIQ-sse</CardTitle><p className="text-sm text-muted-foreground">Metsaomanike CRM ja georuumiline töölaud.</p></CardHeader>
        <CardContent className="space-y-4">
          {error && <p role="alert" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950">{error}</p>}
          <Button asChild className="w-full"><a href={loginHref}>Logi sisse</a></Button>
          {error && <Button variant="outline" className="w-full" onClick={() => void login()}>Proovi uuesti</Button>}
          <p className="rounded-md bg-muted p-3 text-xs text-muted-foreground">Kohaliku arenduskasutaja loob administraator Keycloakis ja lisab kasutajale organisatsiooni atribuudi.</p>
        </CardContent>
      </Card>
    </main>
  );
}

export default function LoginPage() {
  return <Suspense fallback={<main role="status" className="grid min-h-screen place-items-center text-sm text-muted-foreground">Laadin sisselogimist…</main>}><LoginPageContent /></Suspense>;
}
