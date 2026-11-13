#!/bin/sh
set -eu
cd build
jar cfm HentaiAtHome.jar ../src/hath/base/HentaiAtHome.manifest hath/base
jar cfm HentaiAtHomeGUI.jar ../src/hath/gui/HentaiAtHomeGUI.manifest ../src/hath/gui/*.png hath/gui
