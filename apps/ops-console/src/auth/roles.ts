import { jwtDecode } from "jwt-decode";

interface RealmAccessClaims {
  realm_access?: {
    roles?: string[];
  };
}

const OPERATOR_ROLES = ["OPERATOR", "ADMIN"];

export function hasOperatorAccess(accessToken: string | undefined): boolean {
  if (!accessToken) {
    return false;
  }
  const claims = jwtDecode<RealmAccessClaims>(accessToken);
  const roles = claims.realm_access?.roles ?? [];
  return roles.some((role) => OPERATOR_ROLES.includes(role));
}

export function isAdmin(accessToken: string | undefined): boolean {
  if (!accessToken) {
    return false;
  }
  const claims = jwtDecode<RealmAccessClaims>(accessToken);
  const roles = claims.realm_access?.roles ?? [];
  return roles.includes("ADMIN");
}
