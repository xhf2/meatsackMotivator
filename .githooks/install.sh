#!/usr/bin/env bash
# One-time: point git at our tracked hooks directory so pre-commit fires.
# Run from the repo root: ./.githooks/install.sh
set -e
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
echo "Installed. Hook will run on next commit."
