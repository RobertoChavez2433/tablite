# Tablite Quality Analyzer

`python tools/quality/analyzer.py` is the repository's single lint entrypoint.
It combines project-owned architecture policy with native language analyzers.

- Local fast verification permits an explicit "tool unavailable" notice for
  optional host installations.
- CI runs `python tools/quality/analyzer.py --require-external-tools`.
- `python -m unittest tools.quality.tests.test_analyzer` verifies diagnostic
  behavior and timing attribution.
- Timing output is JSON and the command fails at 60 seconds.

Do not add an Android Lint baseline or an analyzer violation baseline. Fix
enforced violations to zero.
