import { describe, expect, it } from 'vitest';
import { displayNameOf } from './displayName';

describe('displayNameOf', () => {
  it('prefers a readable username, then email, then the subject', () => {
    expect(displayNameOf({ sub: 'd036f6bb', preferred_username: 'fan-a', email: 'a@example.invalid' })).toBe('fan-a');
    expect(displayNameOf({ sub: 'd036f6bb', email: 'a@example.invalid' })).toBe('a@example.invalid');
    expect(displayNameOf({ sub: 'd036f6bb', 'cognito:username': 'b1c2' })).toBe('b1c2');
    expect(displayNameOf({ sub: 'd036f6bb', preferred_username: '  ' })).toBe('d036f6bb');
  });

  it('is empty without a profile', () => {
    expect(displayNameOf(undefined)).toBeNull();
  });
});
