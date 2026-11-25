import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import {
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";

import App from "./App";
import { ApiFailure } from "./lib/api";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 8000,

      retry: (failureCount, error) => {
        if (error instanceof ApiFailure) {
          if (
            error.status === 400 ||
            error.status === 401 ||
            error.status === 403 ||
            error.status === 404
          ) {
            return false;
          }
        }

        return failureCount < 1;
      },

      refetchOnWindowFocus: false,
    },
  },
});

const app = (
  <QueryClientProvider client={queryClient}>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </QueryClientProvider>
);

ReactDOM.createRoot(
  document.getElementById("root")!
).render(
  <React.StrictMode>
    {app}
  </React.StrictMode>
);

