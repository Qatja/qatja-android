#!/bin/bash

VERSIONVAL=master

# Use tag as version
if [ $TRAVIS_TAG ]; then
  VERSIONVAL=$TRAVIS_TAG
else
  VERSIONVAL="dev"
fi

sed -i -e "s/@VERSION@/$VERSIONVAL/g" gradle.properties
