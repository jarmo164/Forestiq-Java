"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { defaultWorkspacePath } from "@/lib/workspace-routes";

export default function HomePage() {
  const router = useRouter();
  const { authenticated, roles } = useAuth();

  useEffect(() => {
    if (authenticated) router.replace(defaultWorkspacePath(roles));
  }, [authenticated, roles, router]);

  return <main aria-busy="true" role="status" className="grid min-h-[40vh] place-items-center text-sm text-muted-foreground">Ava töölauda…</main>;
}
