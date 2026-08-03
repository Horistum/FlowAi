#!/bin/bash
# SPDX-License-Identifier: MIT

set -eu

echo "steps:"
echo "  - wait"

find specs/* -type d | while read -r D; do
  echo "  - command: \"$D/test.sh\""
  echo "    label: \"$(basename "$D")\""
done

if [[ "$BUILDKITE_BRANCH" == "main" ]]; then
  echo "  - wait"
  echo "  - command: \"echo Deploy!\""
  echo "    label: \":rocket:\""
fi
