#!/bin/bash

  # reset the file - most likely not needd
  rm -f changeLog.md
  rm -f Tchangelog.htm
  touch changeLog.md
  touch Tchangelog.htm

  #find the last time we made a changelog
  LASTUPDATE=$(git log -100 | grep -B 4 "Version update: Release" | grep "commit" -m 1 | cut -d " " -f 2)
  #find all commits since last release
  COMMITS=$(git rev-list $LASTUPDATE..HEAD)
  #separator is newline
  IFS=$'\n'
  for COMMIT in $COMMITS
  do
    SUBJECT=$(git show $COMMIT --pretty=format:"%s" --no-patch | tr -d '\0')
    # skip merge commits and version bumps
    if echo "$SUBJECT" | grep -qE "^(Merge |Version update: )"; then
      continue
    fi
    # strip CHANGELOG: prefix for backwards compat
    SUBJECT="${SUBJECT##CHANGELOG: }"
    echo "- ${SUBJECT}  " >> changeLog.md
  done
