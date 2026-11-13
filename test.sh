#!/bin/sh
set -eu
cd "$(dirname "$0")"
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
find source/src tests -type f -name '*.java' > "$build_dir/sources.txt"
javac --release 11 -d "$build_dir" "@$build_dir/sources.txt"
java -cp "$build_dir" hath.base.RegressionTests
