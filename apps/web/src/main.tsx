import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { ApiError } from './api/client';
import { router } from './router';
import { AuthProvider } from './auth/AuthProvider';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      // Retrying cannot fix a refused credential; the auth provider ends the session instead.
      retry: (failures, error) =>
        failures < 2 && !(error instanceof ApiError && (error.status === 401 || error.status === 403)),
      retryDelay: (attempt) => Math.min(1_000 * 2 ** attempt, 15_000),
      refetchOnWindowFocus: true,
    },
  },
});

const root = document.querySelector('#root');
if (!(root instanceof HTMLElement)) {
  throw new Error('CourtPulse root element is missing');
}

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
