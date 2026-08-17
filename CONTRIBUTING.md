# Contributing to dogstatsd

Thanks for your interest in improving `dogstatsd`. Bug reports, fixes, and
focused feature contributions are all welcome.

## Before you start

- For a change that is more than a trivial fix, **open an issue first**. This
  lets us agree on the approach before you spend time on it.
- Read the open issues and pull requests to prevent duplicate work.

## Development

This is a Clojure library built with `deps.edn` and the
[Clojure CLI](https://clojure.org/guides/install_clojure); Leiningen is not
required. You need a JDK and the Clojure CLI. See the README for the full set
of aliases.

```bash
clojure -M:test    # run the test suite (compiled with *warn-on-reflection* on)
```

A change must obey these conditions before we merge it:

- **Tests first.** Add or update the tests for the behavior that you change.
  For a bug fix, add a regression test. The test must fail before your fix and
  pass after it.
- **The build passes.** The test suite passes and the build reports **zero**
  reflection warnings.
- **One change per pull request.** Keep each pull request to one logical change.

## Commits and pull requests

- Follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` …).
- Keep the subject in the imperative mood. Keep it under about 72 characters.
- Update `CHANGELOG.md` when your change is user-visible.
- Rebase on the latest `main` before opening the pull request.

## License

If you contribute, you agree to license your contribution under the same
license as this project. See `LICENSE` or the README.
