import { type ReactNode, useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { Center, Loader, Stack, Text, Title } from "@mantine/core";
import { hasOperatorAccess } from "./roles";

export function RequireOperatorAccess({ children }: { children: ReactNode }) {
  const auth = useAuth();

  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator) {
      auth.signinRedirect();
    }
  }, [auth]);

  if (auth.isLoading || (!auth.isAuthenticated && !auth.error)) {
    return (
      <Center h="100vh">
        <Loader aria-label="Signing in" />
      </Center>
    );
  }

  if (auth.error) {
    return (
      <Center h="100vh" p="xl">
        <Stack align="center" gap="xs" maw={480}>
          <Title order={1}>Sign-in failed</Title>
          <Text c="dimmed" ta="center">
            {auth.error.message}
          </Text>
        </Stack>
      </Center>
    );
  }

  if (!hasOperatorAccess(auth.user?.access_token)) {
    return (
      <Center h="100vh" p="xl">
        <Stack align="center" gap="xs" maw={480}>
          <Title order={1}>Forbidden</Title>
          <Text c="dimmed" ta="center">
            This console is for operators and admins. Your account does not have the OPERATOR or
            ADMIN role required to view it.
          </Text>
        </Stack>
      </Center>
    );
  }

  return <>{children}</>;
}
