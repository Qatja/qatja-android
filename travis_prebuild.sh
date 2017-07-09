#!/bin/bash

VERSIONVAL=master

# Use tag as version
if [ $TRAVIS_TAG ]; then
  VERSIONVAL=$TRAVIS_TAG
else
  VERSIONVAL="dev"
fi

sed -e s%@VERSION@%${VERSIONVAL}% ./qatja-android/.properties.original > ./qatja-android/gradle.properties