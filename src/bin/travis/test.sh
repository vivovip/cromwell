#!/usr/bin/env bash

set -e

SCRIPT_DIR=src/bin/travis

# BUILD_TYPE is coming in from the Travis build matrix
if [ "$BUILD_TYPE" = "centaur" ]; then
    "${SCRIPT_DIR}"/testCentaur.sh
elif [ "$BUILD_TYPE" = "sbt" ]; then
    "${SCRIPT_DIR}"/testSbt.sh
else
    echo "Unknown BUILD_TYPE: '$BUILD_TYPE'"
    exit 1
fi
