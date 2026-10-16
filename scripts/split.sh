#!/bin/bash
# Splits an APK into 90 MiB parts (for Drive / phone transfer). Join: cat FILE.part0* > FILE
split -b 90M -d -a 2 "$1" "$1.part"
