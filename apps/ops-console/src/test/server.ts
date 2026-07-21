import { setupServer } from "msw/node";
import { handlers } from "./mswHandlers";

export const server = setupServer(...handlers);
