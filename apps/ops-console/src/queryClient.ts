import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./api/errors";

function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 2;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetry,
      staleTime: 15_000,
    },
    mutations: {
      retry: false,
    },
  },
});
