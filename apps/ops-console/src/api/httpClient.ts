import { ApiError, type ProblemDetail } from "./errors";

export interface RequestOptions {
  method?: "GET" | "POST" | "PATCH";
  accessToken?: string;
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  idempotencyKey?: string;
  ifMatch?: string | number;
}

function buildUrl(baseUrl: string, path: string, query?: RequestOptions["query"]): string {
  const url = new URL(path, baseUrl);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) {
        url.searchParams.set(key, String(value));
      }
    }
  }
  return url.toString();
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail | null> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return null;
  }
}

export async function requestJson<T>(
  baseUrl: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const headers = new Headers({ Accept: "application/json" });
  if (options.accessToken) {
    headers.set("Authorization", `Bearer ${options.accessToken}`);
  }
  if (options.idempotencyKey) {
    headers.set("Idempotency-Key", options.idempotencyKey);
  }
  if (options.ifMatch !== undefined) {
    headers.set("If-Match", String(options.ifMatch));
  }
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(buildUrl(baseUrl, path, options.query), {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseProblemDetail(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export async function requestBlob(
  baseUrl: string,
  path: string,
  options: RequestOptions = {},
): Promise<Blob> {
  const headers = new Headers();
  if (options.accessToken) {
    headers.set("Authorization", `Bearer ${options.accessToken}`);
  }

  const response = await fetch(buildUrl(baseUrl, path, options.query), {
    method: options.method ?? "GET",
    headers,
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseProblemDetail(response));
  }

  return await response.blob();
}
