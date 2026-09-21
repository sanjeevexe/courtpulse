import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      retry: 2,
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
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
