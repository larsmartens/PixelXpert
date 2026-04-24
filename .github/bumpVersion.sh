#!/bin/bash

CURRENTVERCODE=$(sed -n 's/^versionCode=//p' MagiskModBase/module.prop | head -n 1 | tr -d '\r' | tr -d ' ')
NEWVERCODE=$((CURRENTVERCODE + 1))
NEWVERNAME="canary-$NEWVERCODE"

sed -i 's/^[[:space:]]*versionCode = .*/\t\tversionCode = '$NEWVERCODE'/' app/build.gradle.kts
sed -i 's/^[[:space:]]*versionName = .*/\t\tversionName = "'$NEWVERNAME'"/' app/build.gradle.kts

sed -i 's/version=.*/version='$NEWVERNAME'/' MagiskModBase/module.prop
sed -i 's/versionCode=.*/versionCode='$NEWVERCODE'/' MagiskModBase/module.prop

sed -i 's/"version":.*/"version": "'$NEWVERNAME'",/' latestCanary.json
sed -i 's/"versionCode":.*/"versionCode": '$NEWVERCODE',/' latestCanary.json

BUILDDATE=$(date -u +"%Y-%m-%d %H:%M UTC")
sed -i 's/"buildDate":.*/"buildDate": "'"$BUILDDATE"'",/' latestCanary.json

sed -i 's/"version":.*/"version": "'$NEWVERNAME'",/' MagiskModuleUpdate_Xposed.json
sed -i 's/"versionCode":.*/"versionCode": '$NEWVERCODE',/' MagiskModuleUpdate_Xposed.json

sed -i 's/"version":.*/"version": "'$NEWVERNAME'",/' MagiskModuleUpdate_Full.json
sed -i 's/"versionCode":.*/"versionCode": '$NEWVERCODE',/' MagiskModuleUpdate_Full.json

#sed -i 's/"zipUrl_Xposed":.*/"zipUrl_Xposed": "https:\/\/nightly.link\/siavash79\/AOSPMods\/actions\/runs\/'$1'\/AOSPMods_Xposed.zip",/' latestCanary.json
#sed -i 's/"zipUrl_Full":.*/"zipUrl_Full": "https:\/\/nightly.link\/siavash79\/AOSPMods\/actions\/runs\/'$1'\/AOSPMods_Full.zip",/' latestCanary.json

# module changelog
echo "**$NEWVERNAME**  " > newChangeLog.md
cat changeLog.md >> newChangeLog.md
echo "  " >> newChangeLog.md
cat CanaryChangelog.md >> newChangeLog.md
mv  newChangeLog.md CanaryChangelog.md

echo "*$NEWVERNAME* released in canary channel  " > telegram.msg
echo "  " >> telegram.msg
echo "*Changelog:*  " >> telegram.msg
cat changeLog.md >> telegram.msg
echo 'TMessage<<EOF' >> $GITHUB_ENV
cat telegram.msg >> $GITHUB_ENV
echo 'EOF' >> $GITHUB_ENV
