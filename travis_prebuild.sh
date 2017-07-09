#!/bin/bash


search_dir=$PWD
work_dir=$PWD
find "${search_dir}" "${work_dir}" -mindepth 1 -maxdepth 1 -type f -print0 | xargs -0 -I {} echo "{}"

VERSIONVAL=master

# Use tag as version
if [ $TRAVIS_TAG ]; then
  VERSIONVAL=$TRAVIS_TAG
else
  VERSIONVAL="dev"
fi

sed -i '' -e "s/@VERSION@/$VERSIONVAL/g" ./gradle.properties
