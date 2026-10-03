export type WorkspaceRole = "ORG_ADMIN" | "CRM_MANAGER" | "EVALUATOR" | "CALLER" | "VIEWER";

type WorkspaceRoute = {
  href: string;
  roles?: WorkspaceRole[];
};

/**
 * One client-side route policy shared by navigation, deep-link guidance and the
 * authenticated layout. Server/API authorization remains the enforcement layer.
 */
export const workspaceRoutes: WorkspaceRoute[] = [
  { href: "/admin/contracts", roles: ["ORG_ADMIN"] },
  { href: "/admin", roles: ["ORG_ADMIN", "CRM_MANAGER"] },
  { href: "/sales-management", roles: ["ORG_ADMIN", "CRM_MANAGER"] },
  { href: "/evaluation-workdesk", roles: ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR"] },
  { href: "/data-freshness", roles: ["ORG_ADMIN", "CRM_MANAGER"] },
  { href: "/reminders-dashboard", roles: ["ORG_ADMIN", "CRM_MANAGER"] },
  { href: "/my-work", roles: ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER"] },
  { href: "/sales", roles: ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER"] },
  { href: "/deals", roles: ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER"] },
  { href: "/inheritance", roles: ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER"] },
  { href: "/map" },
  { href: "/workbaskets" },
  { href: "/overview" },
  { href: "/owners" },
  { href: "/reminders" },
  { href: "/messages" },
  { href: "/phonebook" },
  { href: "/contracts" },
  { href: "/me" },
];

export function rolesForWorkspacePath(pathname: string): WorkspaceRole[] | undefined {
  const matched = workspaceRoutes.find((route) => pathname === route.href || pathname.startsWith(`${route.href}/`));
  return matched?.roles;
}

export function isWorkspacePathAllowed(pathname: string, roles: readonly string[]) {
  const requiredRoles = rolesForWorkspacePath(pathname);
  return !requiredRoles || requiredRoles.some((role) => roles.includes(role));
}

export function defaultWorkspacePath(roles: readonly string[]) {
  if (roles.includes("CALLER")) return "/sales";
  if (roles.includes("EVALUATOR")) return "/evaluation-workdesk";
  if (roles.includes("CRM_MANAGER") || roles.includes("ORG_ADMIN")) return "/overview";
  return "/map";
}

export function safeInternalPath(value: string | null | undefined) {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) return undefined;
  try {
    const parsed = new URL(value, "https://forestiq.local");
    return parsed.origin === "https://forestiq.local" ? `${parsed.pathname}${parsed.search}${parsed.hash}` : undefined;
  } catch {
    return undefined;
  }
}
