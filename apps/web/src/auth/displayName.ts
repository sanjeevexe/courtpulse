/** The name shown to a signed-in user: a readable claim when the provider sends one, else the subject. */
export function displayNameOf(profile: Record<string, unknown> | undefined): string | null {
  if (!profile) return null;
  for (const claim of ['preferred_username', 'email', 'cognito:username', 'sub']) {
    const value = profile[claim];
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
  }
  return null;
}
