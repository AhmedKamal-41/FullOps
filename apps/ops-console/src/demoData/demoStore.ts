import type { IncidentResponse } from "../api/types";
import { demoIncidents } from "./fixtures";

// Demo mode is stateful within a single browser session so the console's write actions —
// acknowledging, assigning, and resolving an incident — visibly take effect, exactly as
// they would against the real backend. State lives only in memory: a page reload reseeds
// from the static fixtures, so every session starts from the same clean example data.
const incidents: IncidentResponse[] = demoIncidents.content.map((incident) => ({ ...incident }));

export function listDemoIncidents(): IncidentResponse[] {
  return incidents;
}

export function updateDemoIncident(
  incidentId: string,
  patch: Partial<IncidentResponse>,
): IncidentResponse {
  const index = incidents.findIndex((incident) => incident.incidentId === incidentId);
  const base = index >= 0 ? incidents[index] : incidents[0];
  const updated = { ...base, ...patch };
  if (index >= 0) {
    incidents[index] = updated;
  }
  return updated;
}
