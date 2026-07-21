import { Center, Loader } from "@mantine/core";
import { useAuth } from "react-oidc-context";
import { Navigate } from "react-router-dom";

// react-oidc-context's AuthProvider intercepts the code-exchange itself; this route only
// needs to wait for that to finish, then hand off to the real app.
export function Callback() {
  const auth = useAuth();

  if (auth.isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  return (
    <Center h="100vh">
      <Loader aria-label="Completing sign-in" />
    </Center>
  );
}
