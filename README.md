# broadcastmail-worker

Async campaign processor for BroadcastMail. Resolves recipients from users' Supabase databases, sends emails via Resend, and tracks delivery outcomes.

## Responsibilities

- Polls for `RESOLVING` campaigns every 5 seconds
- Fetches recipients from user's Supabase DB via JDBC
- Enforces plan recipient limits during resolution
- Sends emails via user's Resend API key (BYOK)
- Tracks sent, delivered, bounced, failed counts per recipient

See [ARCHITECTURE.md](https://github.com/broadcastmail/.github/blob/main/ARCHITECTURE.md) for how this service fits into the full system.

## Getting started

See [DEVELOPERS.md](DEVELOPERS.md) for local setup.

## Contributing

See [CONTRIBUTING.md](https://github.com/broadcastmail/.github/blob/main/CONTRIBUTING.md).

## License

MIT
