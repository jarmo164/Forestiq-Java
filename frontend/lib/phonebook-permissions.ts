export function canDeletePhonebookEntry(roles: readonly string[]) {
  return roles.includes("ORG_ADMIN") || roles.includes("CRM_MANAGER");
}
