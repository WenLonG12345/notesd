import { ApiError, NetworkError } from '../api/client'

/** Copy for errors any auth form can hit that aren't specific to it. */
export function genericMessage(err: unknown): string {
  if (err instanceof NetworkError) return "Can't reach the server. Check that it's running, then try again."
  if (err instanceof ApiError && err.status === 429) {
    return `Too many attempts. Try again in ${err.retryAfter ?? 60} seconds.`
  }
  return 'Something went wrong on the server. Try again.'
}
