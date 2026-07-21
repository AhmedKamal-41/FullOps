// RFC 9457 Problem Details — every error the backend returns takes this shape (see
// CLAUDE.md's "Use RFC 9457 Problem Details for HTTP errors" rule).
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  [extensionMember: string]: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(status: number, problem: ProblemDetail | null) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}
