import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { GameDetailPage } from './pages/GameDetailPage';
import { GameSlatePage } from './pages/GameSlatePage';
import { NotFoundPage } from './pages/NotFoundPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { MyGamesPage } from './pages/MyGamesPage';
import { MyRulesPage } from './pages/MyRulesPage';
import { MyAlertsPage } from './pages/MyAlertsPage';
import { NotificationSettingsPage } from './pages/NotificationSettingsPage';
import { ReplaysPage } from './pages/ReplaysPage';

export const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { path: '/', element: <GameSlatePage /> },
      { path: '/games/:gameId', element: <GameDetailPage /> },
      { path: '/replays', element: <ReplaysPage /> },
      { path: '/my-games', element: <MyGamesPage /> },
      { path: '/my-rules', element: <MyRulesPage /> },
      { path: '/my-alerts', element: <MyAlertsPage /> },
      { path: '/notification-settings', element: <NotificationSettingsPage /> },
      { path: '/auth/callback', element: <AuthCallbackPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
