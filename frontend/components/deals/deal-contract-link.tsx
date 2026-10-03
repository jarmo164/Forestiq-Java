"use client";

import Link from "next/link";
import { FilePlus2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth";

export function DealContractLink({ dealId }: { dealId: string }) {
  const { hasRole } = useAuth();
  if (!hasRole("ORG_ADMIN", "CRM_MANAGER")) return null;

  return <div className="rounded-md border border-primary/20 bg-primary/5 p-3">
    <p className="text-sm font-medium">Tehing on võidetud ja valmis lepingu vormistamiseks.</p>
    <p className="mt-1 text-xs text-muted-foreground">Lepingu vaade eeltäidab müüja, kinnistud, kokkulepitud hinna ja tingimused aktsepteeritud pakkumisest.</p>
    <Button className="mt-3" size="sm" asChild>
      <Link href={`/contracts?dealId=${encodeURIComponent(dealId)}`}><FilePlus2 className="mr-2 h-4 w-4" />Loo leping</Link>
    </Button>
  </div>;
}
