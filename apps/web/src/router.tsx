import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { GameDetailPage } from './pages/GameDetailPage';
import { GameSlatePage } from './pages/GameSlatePage';
import { NotFoundPage } from './pages/NotFoundPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { MyGamesPage } from './pages/MyGamesPage';

export const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { path: '/', element: <GameSlatePage /> },
      { path: '/games/:gameId', element: <GameDetailPage /> },
      { path: '/my-games', element: <MyGamesPage /> },
      { path: '/auth/callback', element: <AuthCallbackPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
