import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AppShell } from '../components/AppShell';
import { GameDetailPage } from '../pages/GameDetailPage';
import { GameSlatePage } from '../pages/GameSlatePage';
import { NotFoundPage } from '../pages/NotFoundPage';

export function renderApp(route = '/') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: Number.POSITIVE_INFINITY },
    },
  });
  const router = createMemoryRouter([
    {
      element: <AppShell />,
      children: [
        { path: '/', element: <GameSlatePage /> },
        { path: '/games/:gameId', element: <GameDetailPage /> },
        { path: '*', element: <NotFoundPage /> },
      ],
    },
  ], { initialEntries: [route] });
  return {
    queryClient,
    router,
    ...render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  };
}
