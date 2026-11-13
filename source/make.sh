#!/bin/sh
set -eu
mkdir -p build
find src -type f -name '*.java' > build/srcfiles.txt
javac --release 11 -d ./build @build/srcfiles.txt
