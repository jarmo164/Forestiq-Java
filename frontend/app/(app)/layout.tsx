"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { AppShell } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth";
import { isWorkspacePathAllowed } from "@/lib/workspace-routes";

export default function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { initialized, authenticated, authError, roles, retrySession } = useAuth();
  const allowed = isWorkspacePathAllowed(pathname, roles);

  useEffect(() => {
    if (!initialized || authenticated || authError) return;
    const returnTo = `${pathname}${window.location.search}`;
    router.replace(`/login?returnTo=${encodeURIComponent(returnTo)}`);
  }, [authError, authenticated, initialized, pathname, router]);

  if (!initialized) {
    return <main aria-busy="true" role="status" className="grid min-h-screen place-items-center text-sm text-muted-foreground">Laadin tööruumi…</main>;
  }

  if (authError) {
    return <main className="grid min-h-screen place-items-center p-6"><section role="alert" className="w-full max-w-md rounded-lg border bg-white p-6 shadow-sm"><h1 className="text-lg font-semibold">Tööruumi ei saanud avada</h1><p className="mt-2 text-sm text-muted-foreground">{authError}</p><Button className="mt-4" onClick={() => void retrySession()}>Proovi uuesti</Button></section></main>;
  }

  if (!authenticated) {
    return <main aria-busy="true" role="status" className="grid min-h-screen place-items-center text-sm text-muted-foreground">Suuname sisselogimisse…</main>;
  }

  if (!allowed) {
    return <AppShell><section role="alert" className="rounded-lg border border-amber-300 bg-amber-50 p-5 text-sm text-amber-950"><h1 className="font-semibold">Selle vaate avamiseks puudub vajalik õigus</h1><p className="mt-1">Sinu rollile lubatud tööriistad leiad külgmenüüst.</p><Button className="mt-3" variant="outline" onClick={() => router.push("/")}>Ava minu töölaud</Button></section></AppShell>;
  }

  return <AppShell>{children}</AppShell>;
}
