"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { BarChart3, Bell, BookUser, BriefcaseBusiness, Building2, CalendarClock, ClipboardCheck, FileText, LayoutDashboard, LogOut, Mail, Map, Menu, RefreshCw, Scale, Sparkles, UserRound, Users, X } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { useUnnoticedCount } from "@/lib/api";
import { isWorkspacePathAllowed } from "@/lib/workspace-routes";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const navigation = [
  { href: "/map", label: "Kaart", icon: Map },
  { href: "/my-work", label: "Minu töö", icon: CalendarClock },
  { href: "/workbaskets", label: "Töökorvid", icon: BriefcaseBusiness },
  { href: "/overview", label: "Ülevaade", icon: LayoutDashboard },
  { href: "/owners", label: "Omanikud", icon: Users },
  { href: "/sales", label: "Müügipult", icon: Sparkles },
  { href: "/deals", label: "Tehingud", icon: BriefcaseBusiness },
  { href: "/sales-management", label: "Müügijuhtimine", icon: BarChart3 },
  { href: "/evaluation-workdesk", label: "Hindamised", icon: ClipboardCheck },
  { href: "/inheritance", label: "Pärimisregister", icon: Scale },
  { href: "/data-freshness", label: "Andmete värskus", icon: RefreshCw },
  { href: "/reminders", label: "Meeldetuletused", icon: Bell },
  { href: "/reminders-dashboard", label: "Tähtaegade ülevaade", icon: CalendarClock },
  { href: "/messages", label: "Sõnumid", icon: Mail },
  { href: "/phonebook", label: "Kontaktiraamat", icon: BookUser },
  { href: "/contracts", label: "Lepingud", icon: FileText },
  { href: "/me", label: "Minu konto", icon: UserRound },
];

function NavigationLinks({ pathname, roles, unnoticedCount, onNavigate }: {
  pathname: string;
  roles: readonly string[];
  unnoticedCount?: number;
  onNavigate?: () => void;
}) {
  const visibleNavigation = navigation.filter((item) => isWorkspacePathAllowed(item.href, roles));
  const canAccessAdmin = isWorkspacePathAllowed("/admin", roles);
  return <>
    {visibleNavigation.map(({ href, label, icon: Icon }) => (
      <Link key={href} href={href} onClick={onNavigate} className={cn("flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-slate-600 hover:bg-secondary hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring", pathname.startsWith(href) && "bg-secondary text-primary")}>
        <Icon className="h-4 w-4" />
        {label}
        {href === "/messages" && unnoticedCount && unnoticedCount > 0 && <span className="ml-auto rounded-full bg-red-500 px-2 py-0.5 text-xs text-white">{unnoticedCount}</span>}
      </Link>
    ))}
    {canAccessAdmin && (
      <Link href="/admin" onClick={onNavigate} className={cn("flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-slate-600 hover:bg-secondary hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring", pathname.startsWith("/admin") && "bg-secondary text-primary")}>
        <Building2 className="h-4 w-4" />Haldus
      </Link>
    )}
  </>;
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const { username, roles, logout } = useAuth();
  const { data: unnoticed } = useUnnoticedCount();
  const mapWorkspace = pathname.startsWith("/map");

  return (
    <div className="min-h-screen bg-slate-50">
      <a href="#main-content" className="sr-only fixed left-4 top-4 z-[60] rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground focus:not-sr-only focus:outline-none focus:ring-2 focus:ring-ring">Liigu põhisusse</a>
      <aside className="fixed inset-y-0 hidden w-64 flex-col border-r bg-white lg:flex">
        <div className="flex h-16 shrink-0 items-center gap-3 border-b px-6 text-primary"><Building2 className="h-7 w-7" /><span className="text-lg font-bold tracking-tight">ForestIQ</span></div>
        <nav aria-label="Põhinavigeerimine" className="min-h-0 flex-1 space-y-1 overflow-y-auto p-3">
          <NavigationLinks pathname={pathname} roles={roles} unnoticedCount={unnoticed?.count} />
        </nav>
        <div className="shrink-0 border-t p-4">
          <p className="truncate text-sm font-medium">{username ?? "Sisselogitud kasutaja"}</p>
          <p className="mb-3 text-xs text-muted-foreground">Organisatsiooni tööruum</p>
          <Button className="w-full justify-start" variant="ghost" onClick={() => void logout()}><LogOut className="mr-2 h-4 w-4" />Logi välja</Button>
        </div>
      </aside>
      <main id="main-content" tabIndex={-1} className="focus:outline-none lg:pl-64">
        <header className="sticky top-0 z-20 border-b bg-white lg:hidden">
          <div className="flex h-16 items-center justify-between px-4"><span className="font-bold text-primary">ForestIQ</span><Button variant="outline" size="icon" aria-label="Ava menüü" aria-expanded={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)}>{mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}</Button></div>
          {mobileMenuOpen && <nav aria-label="Mobiilne põhinavigeerimine" className="space-y-1 border-t bg-white p-3 shadow-sm"><NavigationLinks pathname={pathname} roles={roles} unnoticedCount={unnoticed?.count} onNavigate={() => setMobileMenuOpen(false)} /><Button className="w-full justify-start" variant="ghost" onClick={() => void logout()}><LogOut className="mr-2 h-4 w-4" />Logi välja</Button></nav>}
        </header>
        <div className={mapWorkspace ? "p-2 lg:p-3" : "container py-8"}>{children}</div>
      </main>
    </div>
  );
}
