import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { homeFor } from '../../utils/format';
import { PageLoader } from '../ui';

/**
 * Route guard for role-based areas. The API enforces RBAC on every request;
 * this guard only keeps users out of screens they could not use anyway.
 */
export default function ProtectedRoute({ roles }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <PageLoader label="Checking your session…" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (roles && !roles.includes(user.role)) return <Navigate to={homeFor(user.role)} replace state={{ denied: true }} />;
  return <Outlet />;
}

/** Login / register pages: signed-in users go straight to their dashboard. */
export function GuestOnly() {
  const { user, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (user) return <Navigate to={homeFor(user.role)} replace />;
  return <Outlet />;
}
