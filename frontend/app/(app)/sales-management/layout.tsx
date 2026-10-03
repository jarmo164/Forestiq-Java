import Link from "next/link";
import { Button } from "@/components/ui/button";

export default function SalesManagementLayout({ children }: { children: React.ReactNode }) {
  return <div className="space-y-5">
    <div className="flex flex-wrap gap-2 border-b pb-4">
      <Button asChild size="sm" variant="outline"><Link href="/sales-management">Juhi töölaud</Link></Button>
      <Button asChild size="sm" variant="outline"><Link href="/sales-management/analytics">Müügianalüütika</Link></Button>
      <Button asChild size="sm" variant="outline"><Link href="/sales-management/segments">Sihtrühmad & tööjaotus</Link></Button>
      <Button asChild size="sm" variant="outline"><Link href="/sales-management/data-quality">Andmekvaliteet</Link></Button>
    </div>
    {children}
  </div>;
}
