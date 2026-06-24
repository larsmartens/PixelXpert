#!/bin/bash

mkdir output
cp app/build/outputs/apk/debug/PixelXpert.apk MagiskModBase/system/priv-app/PixelXpert/PixelXpert.apk
cd MagiskModBase

FILENAME="PixelXpert.zip"
zip -r ../output/$FILENAME *;
